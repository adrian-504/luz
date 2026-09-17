package app.iptvplayer.storage

import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.MovieId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.id.SeriesId
import app.iptvplayer.domain.library.Quality
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentKind
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ExternalIds
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.Series
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.TitleDetail
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.db.IptvDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Film and show details, versions, people and the shelves built from them (ADR-0035). */
private const val SHOWS = 5_000
private const val NAMES = 2_000

class DetailStoreTest {
    private val file: File = File.createTempFile("detail-store", ".db").also { it.delete() }
    private val driver = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    private var now = Instant.parse("2026-09-17T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private val content = ContentStore(driver, clock)
    private val library = LibraryStore(content, clock)
    private val playlist = PlaylistId("8d3f1c2b-4a5e-4f60-9b71-2c3d4e5f6a7b")

    @AfterTest
    fun cleanUp() {
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun addSource() = content.addSource(
        playlist,
        ProviderId("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"),
        "Example TV",
        PlaylistType.XTREAM,
        "http://panel.example.com:8080",
        UrlTemplate("http://panel.example.com:8080"),
        CredentialRef("cred-1"),
        TransportSecurity.CLEARTEXT,
    )

    private fun movie(id: String, title: String, rating: String?, daysAgo: Int, quality: Quality? = null, group: String = "mg_all") = Movie(
        MovieId(id), playlist, listOf(GroupId(group)), title, 2020, null, null, emptyList(), rating, null, null, null,
        listOf(MediaSourceId("ms_$id")), id.substringAfter('_'), ExternalIds(), now - daysAgo.days, quality = quality,
    )

    private fun importMovies(vararg movies: Movie) {
        now += 1.minutes
        val writer = library.beginSnapshot(playlist, ImportUnit.MOVIES)
        writer.group(ChannelGroup(GroupId("mg_all"), playlist, ContentKind.MOVIE, "All", 0, null))
        writer.group(ChannelGroup(GroupId("mg_4k"), playlist, ContentKind.MOVIE, "4K", 1, null))
        for (movie in movies) {
            writer.movie(
                movie,
                MediaSource(
                    MediaSourceId("ms_${movie.id.value}"),
                    ContentRef(ContentType.MOVIE, movie.id.value),
                    MediaLocator.XtreamStream(XtreamStreamKind.MOVIE, movie.id.value.substringAfter('_'), "mp4"),
                    StreamProtocol.PROGRESSIVE_MP4,
                    MediaHeaders(),
                    null,
                    null,
                    0,
                ),
                null,
            )
        }
        writer.publish()
    }

    @Test
    fun detailsSurviveARefreshAndTheirPeopleAndGenresAreSearchable() {
        addSource()
        importMovies(movie("mv_1", "Heat", "8.3", 10), movie("mv_2", "Collateral", "7.5", 5))
        assertEquals(listOf("mv_2" to "2", "mv_1" to "1"), library.moviesMissingDetail(playlist, 10), "newest first, with stream ids")

        library.saveDetail(
            playlist,
            ContentType.MOVIE,
            "mv_1",
            TitleDetail(
                plot = "A heist.",
                genres = listOf("Crime", "Thriller"),
                cast = listOf("Al Pacino", "Robert De Niro"),
                directors = listOf("Michael Mann"),
            ),
        )
        library.saveDetail(
            playlist,
            ContentType.MOVIE,
            "mv_2",
            TitleDetail(genres = listOf("Crime"), cast = listOf("Tom Cruise"), directors = listOf("Michael Mann")),
        )
        importMovies(movie("mv_1", "Heat", "8.3", 10), movie("mv_2", "Collateral", "7.5", 5))

        assertEquals("A heist.", library.detail(playlist, ContentType.MOVIE, "mv_1")?.plot, "details are kept across a refresh")
        assertEquals("A heist.", library.movie(playlist, "mv_1")?.plot, "and fill in what the list left out")
        assertEquals(listOf("Crime", "Thriller"), library.movie(playlist, "mv_1")?.genres)
        assertEquals(emptyList(), library.moviesMissingDetail(playlist, 10))
        val mann = library.searchPeople(playlist, "mann", 10).single()
        assertEquals(PersonHit("Michael Mann", titles = 2, directs = true), mann)
        assertEquals(listOf("Robert De Niro"), library.searchPeople(playlist, "niro", 10).map { it.name })
        assertEquals(setOf("Heat", "Collateral"), library.titlesOfPerson(playlist, "Michael Mann").first.map { it.title }.toSet())
        assertEquals(listOf("Crime" to 2L, "Thriller" to 1L), library.movieGenres(playlist, 10))
        assertEquals(listOf("Heat"), library.moviesOfGenre(playlist, "Thriller", 10).map { it.title })
    }

    @Test
    fun anEmptyAnswerIsRememberedSoItIsNotAskedForAgain() {
        addSource()
        importMovies(movie("mv_1", "Heat", null, 1))
        library.saveDetail(playlist, ContentType.MOVIE, "mv_1", null)
        assertEquals(emptyList(), library.moviesMissingDetail(playlist, 10))
        assertNull(library.detail(playlist, ContentType.MOVIE, "mv_1")?.plot)
    }

    @Test
    fun versionsOfOneFilmShowAsOneCardPreferringTheBestQualityExceptInsideACategory() {
        addSource()
        importMovies(
            movie("mv_hd", "Dune", "8", 3, Quality.HD),
            movie("mv_4k", "Dune", "8", 2, Quality.UHD, group = "mg_4k"),
            movie("mv_other", "Arrival", "7.9", 1),
        )
        val all = library.movies(playlist)
        assertEquals(listOf("mv_4k", "mv_other").toSet(), all.map { it.id }.toSet(), "one card per film, the 4K version")
        assertEquals(2, all.single { it.id == "mv_4k" }.versionCount)
        assertEquals(Quality.UHD, all.single { it.id == "mv_4k" }.quality)
        assertEquals(listOf("mv_4k", "mv_hd"), library.movieVersions(playlist, "mv_hd").map { it.id }, "best version first")
        assertEquals(listOf("mv_hd", "mv_other"), library.movies(playlist, "mg_all").map { it.id }, "a category lists what it holds")
    }

    @Test
    fun shelvesOrderByRatingByRecencyAndByNewestEpisode() {
        addSource()
        importMovies(movie("mv_old", "Old Classic", "9", 2000), movie("mv_new", "New Hit", "8", 3), movie("mv_none", "Unrated", null, 1))
        assertEquals(listOf("Old Classic", "New Hit"), library.topRatedMovies(playlist, 10).map { it.title })
        assertEquals(listOf("New Hit", "Old Classic"), library.popularMovies(playlist, 10).map { it.title }, "recent and well rated wins")
        // A fetched page's rating replaces the list's, now and after the next refresh.
        library.saveDetail(playlist, ContentType.MOVIE, "mv_new", TitleDetail(rating = "9.5"))
        assertEquals("New Hit", library.topRatedMovies(playlist, 10).first().title)
        importMovies(movie("mv_old", "Old Classic", "9", 2000), movie("mv_new", "New Hit", "8", 3), movie("mv_none", "Unrated", null, 1))
        assertEquals("New Hit", library.topRatedMovies(playlist, 10).first().title, "kept across a refresh")

        val writer = library.beginSnapshot(playlist, ImportUnit.SERIES)
        writer.group(ChannelGroup(GroupId("sg_all"), playlist, ContentKind.SERIES, "All", 0, null))
        listOf("se_a" to 30, "se_b" to 1).forEach { (id, daysAgo) ->
            writer.series(
                Series(
                    SeriesId(id), playlist, listOf(GroupId("sg_all")), "Show $id", null, null, listOf("Drama"), "7", null, null, id,
                    ExternalIds(), now - daysAgo.days, detail = TitleDetail(cast = listOf("Example Actor")),
                ),
                null,
                null,
            )
        }
        writer.publish()
        assertEquals(listOf("se_b", "se_a"), library.newEpisodeSeries(playlist, 10).map { it.id })
        assertEquals(listOf("Drama" to 2L), library.seriesGenres(playlist, 10), "a show's genres come with the list")
        assertEquals(2, library.titlesOfPerson(playlist, "Example Actor").second.size, "and so does its cast")
    }

    @Test
    fun myListAndTheCoverageCheck() {
        addSource()
        importMovies(movie("mv_1", "Heat", "8", 1), movie("mv_2", "Collateral", "7", 1))
        content.setFavorite(ContentType.MOVIE, "mv_2", true)
        assertEquals(listOf("Collateral"), library.favoriteMovies(playlist, 10).map { it.title })

        library.saveDetail(playlist, ContentType.MOVIE, "mv_1", TitleDetail(plot = "A heist.", trailer = "abc"))
        library.saveDetail(playlist, ContentType.MOVIE, "mv_2", TitleDetail(plot = "A night."))
        val coverage = assertNotNull(library.detailCoverage(playlist).singleOrNull { it.type == ContentType.MOVIE })
        assertEquals(2, coverage.fetched)
        assertEquals(2, coverage.plot)
        assertEquals(1, coverage.trailer)
        assertEquals(0, coverage.cast)
    }

    /**
     * Replacing a title's people must not scan everyone in the library. It did (a DELETE on a full-text index filtered by
     * columns the index cannot look up), and a provider's ten thousand shows took long enough to import that the provider
     * closed the connection (8.sqm). Five thousand shows with five people each, from a pool of two thousand names.
     */
    @Test
    fun peopleForThousandsOfTitlesAreWrittenWithoutScanningEveryone() {
        addSource()
        val started = System.nanoTime()
        val writer = library.beginSnapshot(playlist, ImportUnit.SERIES)
        writer.group(ChannelGroup(GroupId("sg_all"), playlist, ContentKind.SERIES, "All", 0, null))
        repeat(SHOWS) { index ->
            writer.series(
                Series(
                    SeriesId("se_$index"), playlist, listOf(GroupId("sg_all")), "Show $index", null, null, emptyList(), null, null, null,
                    "$index", ExternalIds(), now, detail = TitleDetail(cast = (0 until 5).map { "Person ${(index * 7 + it) % NAMES}" }),
                ),
                null,
                null,
            )
        }
        writer.publish()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed < 20_000, "5,000 shows with cast imported in $elapsed ms")
        val hit = library.searchPeople(playlist, "Person 42", 50).first { it.name == "Person 42" }
        assertEquals(library.titlesOfPerson(playlist, "Person 42").second.size, hit.titles)
        assertTrue(hit.titles > 0)
    }

    @Test
    fun theChannelsWatchedMostComeFirst() {
        addSource()
        repeat(3) { library.recordChannelWatch(playlist, "ch_b") }
        library.recordChannelWatch(playlist, "ch_a")
        assertEquals(listOf("ch_b", "ch_a"), library.mostWatchedChannelIds(playlist, 10))
        assertTrue(library.mostWatchedChannelIds(PlaylistId("other"), 10).isEmpty())
    }
}
