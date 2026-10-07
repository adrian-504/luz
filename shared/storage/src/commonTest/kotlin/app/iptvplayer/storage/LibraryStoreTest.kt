package app.iptvplayer.storage

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.EpisodeId
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.MovieId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.id.SeasonId
import app.iptvplayer.domain.id.SeriesId
import app.iptvplayer.domain.model.Channel
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentKind
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.model.Episode
import app.iptvplayer.domain.model.ExternalIds
import app.iptvplayer.domain.model.IdentityHints
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.Season
import app.iptvplayer.domain.model.Series
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.db.IptvDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LibraryStoreTest {
    private val file = TestDatabaseFile("library-store")
    private var driver = openIptvDatabase(file.path)
    private var now = Instant.parse("2026-09-15T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private var content = ContentStore(driver, clock)
    private var library = LibraryStore(content, clock)
    private val playlist = PlaylistId("8d3f1c2b-4a5e-4f60-9b71-2c3d4e5f6a7b")

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
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

    private fun source(type: ContentType, id: String, kind: XtreamStreamKind) = MediaSource(
        MediaSourceId("ms_$id"),
        ContentRef(type, id),
        MediaLocator.XtreamStream(kind, id.substringAfter('_'), "mp4"),
        StreamProtocol.PROGRESSIVE_MP4,
        MediaHeaders(),
        null,
        null,
        0,
    )

    private fun group(id: String, kind: ContentKind) = ChannelGroup(GroupId(id), playlist, kind, id.substringAfter('_'), 0, null)

    private fun movie(id: String, title: String, groups: List<String>, addedMinutesAgo: Int) = Movie(
        MovieId(id), playlist, groups.map(::GroupId), title, 2020, 100.minutes, "Plot of $title", listOf("Drama", "Test"), "7.1", null,
        null, null, listOf(MediaSourceId("ms_$id")), id.substringAfter('_'), ExternalIds(), now - addedMinutesAgo.minutes,
    )

    private fun importMovies(vararg movies: Movie) {
        now += 1.minutes
        val writer = library.beginSnapshot(playlist, ImportUnit.MOVIES, batchSize = 2)
        writer.group(group("mg_action", ContentKind.MOVIE))
        writer.group(group("mg_kids", ContentKind.MOVIE))
        for (movie in movies) {
            writer.movie(
                movie,
                source(ContentType.MOVIE, movie.id.value, XtreamStreamKind.MOVIE),
                UrlTemplate("https://img.example.com/${movie.id.value}.jpg"),
            )
        }
        writer.publish()
    }

    private fun importLiveChannel() {
        val writer = content.beginLiveSnapshot(playlist)
        writer.group(group("lg_news", ContentKind.LIVE))
        writer.channel(
            Channel(
                ChannelId("ch_1"), playlist, listOf(GroupId("lg_news")), "News", null, null, null, null, emptyList(), emptyList(), null,
                listOf(MediaSourceId("ms_ch_1")), null, emptyMap(), IdentityHints(null, null, "news"),
            ),
            source(ContentType.CHANNEL, "ch_1", XtreamStreamKind.LIVE),
            null,
        )
        writer.publish()
    }

    @Test
    fun moviesArePublishedByCategoryAndReplacedWithoutTouchingLiveChannels() {
        addSource()
        importLiveChannel()
        assertTrue(library.movies(playlist).isEmpty(), "nothing before the first publish")

        importMovies(movie("mv_1", "Alpha", listOf("mg_action"), 30), movie("mv_2", "Beta", listOf("mg_action", "mg_kids"), 5))
        assertEquals(listOf("action" to 2L, "kids" to 1L), library.groups(playlist, ImportUnit.MOVIES).map { it.title to it.itemCount })
        assertEquals(listOf("Beta"), library.movies(playlist, "mg_kids").map { it.title })
        assertEquals(listOf("Beta", "Alpha"), library.recentMovies(playlist, 10).map { it.title })
        val alpha = assertNotNull(library.movie(playlist, "mv_1"))
        assertEquals(listOf("Drama", "Test"), alpha.genres)
        assertEquals(100.minutes, alpha.duration)
        assertEquals("https://img.example.com/mv_1.jpg", alpha.poster?.template)
        assertEquals(listOf("Alpha"), library.movies(playlist, limit = 1).map { it.title }, "paged")
        assertEquals(
            MediaLocator.XtreamStream(XtreamStreamKind.MOVIE, "1", "mp4"),
            library.mediaSources(playlist, ContentType.MOVIE, "mv_1").single().locator,
        )

        content.setFavorite(ContentType.MOVIE, "mv_2", true)
        importMovies(movie("mv_2", "Beta", listOf("mg_kids"), 5), movie("mv_3", "Gamma", listOf("mg_action"), 1))
        assertEquals(listOf("Beta", "Gamma"), library.movies(playlist).map { it.title }, "the previous movie snapshot is gone")
        assertTrue(library.movie(playlist, "mv_2")!!.isFavorite, "favorites survive a refresh")
        assertTrue(library.mediaSources(playlist, ContentType.MOVIE, "mv_1").isEmpty())
        assertEquals(1, content.mediaSources(playlist, ChannelId("ch_1")).size, "live channel streams are untouched")
        assertEquals(2, library.movieCount(playlist))
    }

    @Test
    fun seriesWithSeasonsAndEpisodesAndLazyDetailReplacement() {
        addSource()
        val series = Series(
            SeriesId("se_1"), playlist, listOf(GroupId("sg_drama")), "Example Show", 2019, "A show", listOf("Drama"), null, null, null,
            "501", ExternalIds(), null,
        )
        val writer = library.beginSnapshot(playlist, ImportUnit.SERIES)
        writer.group(group("sg_drama", ContentKind.SERIES))
        writer.series(series, UrlTemplate("https://img.example.com/se_1.jpg"), null)
        val season1 = Season(SeasonId("ss_1"), SeriesId("se_1"), 1, null, null, null)
        writer.season(season1, null)
        writer.episode(episode("ep_2", "ss_1", 2), source(ContentType.EPISODE, "ep_2", XtreamStreamKind.SERIES), null)
        writer.episode(episode("ep_1", "ss_1", 1), source(ContentType.EPISODE, "ep_1", XtreamStreamKind.SERIES), null)
        writer.publish()

        assertEquals("Example Show", library.series(playlist, "sg_drama").single().title)
        assertEquals(listOf(1 to 1, 1 to 2), library.episodes(playlist, "se_1").map { it.seasonNumber to it.episodeNumber })
        assertFalse(library.hasSeriesDetail(playlist, "se_1"))

        val season2 = Season(SeasonId("ss_2"), SeriesId("se_1"), 2, "Season Two", null, 10)
        assertTrue(
            library.replaceSeriesDetail(
                playlist,
                "se_1",
                listOf(season1, season2),
                mapOf("ss_2" to UrlTemplate("https://img.example.com/ss_2.jpg")),
                listOf(
                    Triple(episode("ep_1", "ss_1", 1), source(ContentType.EPISODE, "ep_1", XtreamStreamKind.SERIES), null),
                    Triple(episode("ep_9", "ss_2", 1), source(ContentType.EPISODE, "ep_9", XtreamStreamKind.SERIES), null),
                ),
            ),
        )
        assertTrue(library.hasSeriesDetail(playlist, "se_1"))
        assertEquals(listOf("Season Two"), library.seasons(playlist, "se_1").mapNotNull { it.title })
        assertEquals(listOf("ep_1", "ep_9"), library.episodes(playlist, "se_1").map { it.id })
        assertTrue(library.mediaSources(playlist, ContentType.EPISODE, "ep_2").isEmpty(), "replaced episodes lose their streams")
        assertEquals(2 to 1, library.episode(playlist, "ep_9")!!.let { it.seasonNumber to it.episodeNumber })
    }

    private fun episode(id: String, season: String, number: Int) = Episode(
        EpisodeId(id), SeasonId(season), SeriesId("se_1"), number, "Episode $number", null, 42.minutes, null, null,
        listOf(MediaSourceId("ms_$id")), id,
    )

    @Test
    fun watchProgressFeedsContinueWatchingAndSurvivesRefreshes() {
        addSource()
        importMovies(movie("mv_1", "Alpha", listOf("mg_action"), 30), movie("mv_2", "Beta", listOf("mg_action"), 5))

        library.saveProgress(playlist, ContentType.MOVIE, "mv_1", null, 30.minutes, 100.minutes, newSession = true)
        now += 1.minutes
        library.saveProgress(playlist, ContentType.EPISODE, "ep_1", "se_1", 12.minutes, 42.minutes, newSession = true)
        now += 1.minutes
        library.saveProgress(playlist, ContentType.MOVIE, "mv_2", null, 5.seconds, 100.minutes, newSession = true)
        assertEquals(listOf("ep_1", "mv_1"), library.continueWatching(playlist, 10).map { it.id }, "recent first; a 5 s look is not listed")
        assertEquals(0.3f, library.movie(playlist, "mv_1")!!.progress!!.fraction)
        assertEquals("ep_1" to 12.minutes, library.lastWatchedEpisode("se_1")!!.let { it.first to it.second.position })

        importMovies(movie("mv_1", "Alpha", listOf("mg_action"), 30))
        assertEquals(30.minutes, library.movie(playlist, "mv_1")!!.progress!!.position, "progress survives a refresh")

        library.saveProgress(playlist, ContentType.MOVIE, "mv_1", null, 96.minutes, 100.minutes)
        val done = assertNotNull(library.progress(playlist, ContentType.MOVIE, "mv_1"))
        assertTrue(done.completed)
        assertEquals(listOf("ep_1"), library.continueWatching(playlist, 10).map { it.id }, "finished movies leave the list")

        content.deleteSource(playlist)
        assertNull(library.progress(playlist, ContentType.EPISODE, "ep_1"), "removing the source removes its watch history")
        assertTrue(library.movies(playlist).isEmpty())
    }

    @Test
    fun hiddenFilmsAndSeriesLeaveEveryListAndKeepTheirTitlesForTheHiddenList() {
        addSource()
        importMovies(movie("mv_1", "Alpha", listOf("mg_action"), 2), movie("mv_2", "Beta", listOf("mg_action"), 1))
        assertEquals(listOf("mv_1", "mv_2"), library.movies(playlist).map { it.id })

        content.hide(playlist, CustomisationTarget.MOVIE, "mv_2")
        assertEquals(listOf("mv_1"), library.movies(playlist).map { it.id }, "hidden in the grid")
        assertEquals(listOf("mv_1"), library.movies(playlist, "mg_action").map { it.id }, "and in its category")
        assertTrue(library.searchMovies(playlist, "Beta", 10).isEmpty(), "and in search")
        assertEquals(
            mapOf("mv_2" to "Beta"),
            library.titles(playlist, ImportUnit.MOVIES, listOf("mv_2")),
            "its title is still there, so the hidden list can offer it back by name",
        )

        content.unhide(playlist, CustomisationTarget.MOVIE, "mv_2")
        assertEquals(listOf("mv_1", "mv_2"), library.movies(playlist).map { it.id })
    }

    @Test
    fun searchRanksExactThenPrefixThenRestAndTreatsWildcardsLiterally() {
        addSource()
        importLiveChannel()
        importMovies(
            movie("mv_1", "The Test", listOf("mg_action"), 3),
            movie("mv_2", "Test", listOf("mg_action"), 2),
            movie("mv_3", "Testing 100%", listOf("mg_action"), 1),
        )
        assertEquals(listOf("Test", "Testing 100%", "The Test"), library.searchMovies(playlist, " test ", 10).map { it.title })
        // ADR-0029: the index matches whole words and word beginnings, so every word typed has to match somewhere in the title.
        assertEquals(listOf("Testing 100%"), library.searchMovies(playlist, "test 100", 10).map { it.title }, "every word matches")
        assertTrue(library.searchMovies(playlist, "test zulu", 10).isEmpty(), "a word that matches nothing rules the title out")
        assertEquals(listOf("Testing 100%"), library.searchMovies(playlist, "100%", 10).map { it.title }, "% is not a wildcard")
        assertTrue(library.searchMovies(playlist, "%", 10).isEmpty(), "% is not a wildcard")
        assertTrue(library.searchMovies(playlist, "_", 10).isEmpty(), "_ is not a wildcard")
        // If OR were read as an operator this would return "Test"; it is a word nothing starts with, so there is no match.
        assertTrue(library.searchMovies(playlist, "\"test\" OR", 10).isEmpty(), "FTS syntax in a query is literal text")
        assertTrue(library.searchMovies(playlist, "   ", 10).isEmpty())
        assertEquals(listOf("News"), content.searchChannels(playlist, "NEW", 10).map { it.name }, "case-insensitive")
        assertEquals(1, library.searchMovies(playlist, "test", 1).size, "limit")
    }

    @Test
    fun existingVersionOneDatabasesAreUpgradedInPlace() {
        addSource()
        importLiveChannel()
        content.setFavorite(ChannelId("ch_1"), true)
        // Recreate what a Phase 7 install has on disk: no library tables, schema version 1.
        for (table in listOf(
            "snapshot_allocation", "library_group", "library_member", "movie", "series", "season", "episode",
            "series_detail_state", "watch_state", "title_search", "user_hidden", "user_label", "user_group",
            "user_group_member", "app_setting", "title_detail", "title_genre", "person", "title_person", "person_name", "channel_watch",
            "tmdb_title", "tmdb_art", "tmdb_person", "tmdb_episode", "tmdb_season", "user_pinned",
        )) {
            driver.execute(null, "DROP TABLE $table", 0)
        }
        driver.execute(null, "DROP INDEX channel_member_order", 0)
        driver.execute(null, "DROP INDEX favorite_work", 0)
        driver.execute(null, "ALTER TABLE favorite DROP COLUMN work", 0)
        driver.execute(null, "PRAGMA user_version=1", 0)
        driver.close()

        driver = openIptvDatabase(file.path)
        content = ContentStore(driver, clock)
        library = LibraryStore(content, clock)
        assertEquals(
            15L,
            driver.executeQuery(null, "PRAGMA user_version", {
                it.next()
                app.cash.sqldelight.db.QueryResult.Value(it.getLong(0))
            }, 0).value,
        )
        assertEquals("Example TV", content.sources().single().name, "sources survive the upgrade")
        assertTrue(content.channels(playlist).single().isFavorite, "channels and favorites survive the upgrade")
        assertEquals(
            listOf("News"),
            content.searchChannels(playlist, "news", 10).map { it.name },
            "the upgrade indexes what is already imported, so search works without a refresh",
        )
        importMovies(movie("mv_1", "Alpha", listOf("mg_action"), 1))
        assertEquals("Alpha", library.movies(playlist).single().title, "the new tables work after the upgrade")
    }

    private fun importSeries(episodes: Int) {
        now += 1.minutes
        val writer = library.beginSnapshot(playlist, ImportUnit.SERIES)
        writer.group(group("sg_drama", ContentKind.SERIES))
        writer.series(
            Series(
                SeriesId("se_1"), playlist, listOf(GroupId("sg_drama")), "Example Show", 2019, "A show", listOf("Drama"), null, null, null,
                "501", ExternalIds(), null,
            ),
            null,
            null,
        )
        if (episodes > 0) writer.season(Season(SeasonId("ss_1"), SeriesId("se_1"), 1, null, null, null), null)
        for (number in 1..episodes) {
            writer.episode(episode("ep_$number", "ss_1", number), source(ContentType.EPISODE, "ep_$number", XtreamStreamKind.SERIES), null)
        }
        writer.publish()
    }

    @Test
    fun aFilmIsWatchedAndKeptOnceWhicheverVersionIsPlayed() {
        addSource()
        // Two versions of one film — same title and year — and another film.
        importMovies(
            movie("mv_1", "Alpha", listOf("mg_action"), 30),
            movie("mv_1b", "Alpha", listOf("mg_action"), 30),
            movie("mv_2", "Beta", listOf("mg_action"), 5),
        )
        assertEquals("mv_1", library.primaryVersion(playlist, "mv_1b"))

        library.saveProgress(playlist, ContentType.MOVIE, "mv_1b", null, 30.minutes, 100.minutes, newSession = true)
        assertEquals(30.minutes, library.movie(playlist, "mv_1")!!.progress!!.position, "the film's page shows it on any version")
        assertEquals(30.minutes, library.progress(playlist, ContentType.MOVIE, "mv_1")!!.position)
        assertEquals("mv_1b", library.watchedVersion(playlist, "mv_1"), "Resume goes back to the version that was played")
        assertEquals(listOf("mv_1b"), library.continueWatching(playlist, 10).map { it.id })

        now += 1.minutes
        library.saveProgress(playlist, ContentType.MOVIE, "mv_1", null, 40.minutes, 100.minutes)
        assertEquals(listOf("mv_1"), library.continueWatching(playlist, 10).map { it.id }, "another version, still one entry")
        assertEquals(40.minutes, library.movie(playlist, "mv_1b")!!.progress!!.position)
        assertNull(library.movie(playlist, "mv_2")!!.progress, "another film is untouched")

        library.setMovieFavorite(playlist, "mv_1b", true)
        assertTrue(library.movie(playlist, "mv_1")!!.isFavorite, "kept from one version, kept on every version")
        library.setMovieFavorite(playlist, "mv_1", true)
        assertEquals(listOf("mv_1"), library.favoriteMovies(playlist, 10).map { it.id }, "listed once, as its main version")
        library.setMovieFavorite(playlist, "mv_1", false)
        assertFalse(library.movie(playlist, "mv_1b")!!.isFavorite)
        assertTrue(library.favoriteMovies(playlist, 10).isEmpty())
    }

    @Test
    fun continueWatchingFollowsASeriesToItsNextEpisode() {
        addSource()
        importSeries(episodes = 3)

        library.saveProgress(playlist, ContentType.EPISODE, "ep_1", "se_1", 10.minutes, 42.minutes, newSession = true)
        library.continueWatching(playlist, 10).single().let {
            assertEquals("ep_1", it.id)
            assertFalse(it.next)
            assertEquals(10.minutes, it.progress!!.position)
        }

        now += 1.minutes
        library.saveProgress(playlist, ContentType.EPISODE, "ep_1", "se_1", 41.minutes, 42.minutes)
        library.continueWatching(playlist, 10).single().let {
            assertEquals("ep_2", it.id, "after a finished episode, the one that follows")
            assertTrue(it.next)
            assertNull(it.progress)
            assertEquals(1 to 2, it.season to it.episode)
        }

        // A refresh reads the provider's list again and the episodes are gone until the series is opened: still offered.
        importSeries(episodes = 0)
        library.continueWatching(playlist, 10).single().let {
            assertEquals("ep_1", it.id)
            assertTrue(it.needsEpisodes)
            assertEquals(1 to 1, it.season to it.episode, "where the viewer was is remembered without the episode list")
        }
        importSeries(episodes = 3)
        assertEquals("ep_2", library.continueWatching(playlist, 10).single().id)

        now += 1.minutes
        library.saveProgress(playlist, ContentType.EPISODE, "ep_2", "se_1", 5.minutes, 42.minutes, newSession = true)
        library.continueWatching(playlist, 10).single().let {
            assertEquals("ep_2", it.id, "one entry for the series, not one for every episode")
            assertFalse(it.next)
        }

        library.dismissFromContinue(playlist, ContentType.EPISODE, "ep_2")
        assertTrue(library.continueWatching(playlist, 10).isEmpty(), "taken off the list")
        assertEquals(5.minutes, library.episode(playlist, "ep_2")!!.progress!!.position, "its progress is kept")
        now += 1.minutes
        library.saveProgress(playlist, ContentType.EPISODE, "ep_2", "se_1", 6.minutes, 42.minutes)
        assertEquals("ep_2", library.continueWatching(playlist, 10).single().id, "playing it again puts it back")

        now += 1.minutes
        library.saveProgress(playlist, ContentType.EPISODE, "ep_3", "se_1", 42.minutes, 42.minutes, ended = true)
        assertTrue(library.continueWatching(playlist, 10).isEmpty(), "a series that was watched to the end leaves the list")
    }

    @Test
    fun filmsEpisodesAndSeriesCanBeMarkedAsWatchedAndNotWatched() {
        addSource()
        importMovies(movie("mv_1", "Alpha", listOf("mg_action"), 30), movie("mv_1b", "Alpha", listOf("mg_action"), 30))
        importSeries(episodes = 3)

        library.saveProgress(playlist, ContentType.MOVIE, "mv_1", null, 30.minutes, 100.minutes, newSession = true)
        library.setWatched(playlist, ContentType.MOVIE, "mv_1b", true)
        assertTrue(library.movie(playlist, "mv_1")!!.progress!!.completed)
        assertTrue(library.continueWatching(playlist, 10).isEmpty(), "a watched film is not something to continue")
        library.setWatched(playlist, ContentType.MOVIE, "mv_1", false)
        assertNull(library.movie(playlist, "mv_1b")!!.progress)

        now += 1.minutes
        library.setWatched(playlist, ContentType.EPISODE, "ep_1", true)
        assertEquals("ep_2", library.continueWatching(playlist, 10).single().id, "marking an episode moves the series on")
        library.setWatched(playlist, ContentType.EPISODE, "ep_1", false)
        assertTrue(library.continueWatching(playlist, 10).isEmpty())

        now += 1.minutes
        library.setWatched(playlist, ContentType.SERIES, "se_1", true)
        assertTrue(library.episodes(playlist, "se_1").all { it.progress?.completed == true })
        assertTrue(library.continueWatching(playlist, 10).isEmpty(), "a series marked as watched is finished")
        library.setWatched(playlist, ContentType.SERIES, "se_1", false)
        assertTrue(library.episodes(playlist, "se_1").all { it.progress == null })
    }

    @Test
    fun whatWasWatchedAndKeptBeforeWorksIsCarriedOverToTheFilm() {
        addSource()
        importMovies(
            movie("mv_1", "Alpha", listOf("mg_action"), 30),
            movie("mv_1b", "Alpha", listOf("mg_action"), 30),
            movie("mv_2", "Beta", listOf("mg_action"), 5),
        )
        importSeries(episodes = 2)
        // Recreate what schema 14 has on disk: state kept per file, with two versions of one film both watched and kept.
        for (index in listOf("watch_work", "watch_parent", "favorite_work")) driver.execute(null, "DROP INDEX $index", 0)
        for (column in listOf("work", "dismissed", "season_number", "episode_number")) {
            driver.execute(null, "ALTER TABLE watch_state DROP COLUMN $column", 0)
        }
        driver.execute(null, "ALTER TABLE favorite DROP COLUMN work", 0)
        val p = playlist.value
        fun watched(type: String, id: String, parent: String?, position: Long, at: Long) = driver.execute(
            null,
            "INSERT INTO watch_state" +
                "(content_type, content_id, playlist_id, parent_id, position_ms, duration_ms, completed, last_played_at)" +
                " VALUES ('$type', '$id', '$p', ${parent?.let { "'$it'" } ?: "NULL"}, $position, 6000000, 0, $at)",
            0,
        )
        watched("MOVIE", "mv_1", null, 600_000, 1_000)
        watched("MOVIE", "mv_1b", null, 1_800_000, 2_000)
        watched("MOVIE", "mv_2", null, 300_000, 3_000)
        watched("EPISODE", "ep_2", "se_1", 120_000, 4_000)
        for ((order, id) in listOf("mv_1b", "mv_1").withIndex()) {
            val kept = "INSERT INTO favorite(content_type, content_id, sort_order, created_at) VALUES ('MOVIE', '$id', $order, $order)"
            driver.execute(null, kept, 0)
        }
        driver.execute(null, "PRAGMA user_version=14", 0)
        driver.close()

        driver = openIptvDatabase(file.path)
        content = ContentStore(driver, clock)
        library = LibraryStore(content, clock)

        assertEquals(30.minutes, library.movie(playlist, "mv_1")!!.progress!!.position, "the version played last speaks for the film")
        assertEquals("mv_1b", library.watchedVersion(playlist, "mv_1"))
        assertEquals(listOf("ep_2", "mv_2", "mv_1b"), library.continueWatching(playlist, 10).map { it.id }, "each film once")
        assertEquals(1 to 2, library.continueWatching(playlist, 10).first().let { it.season to it.episode })
        assertEquals(listOf("mv_1"), library.favoriteMovies(playlist, 10).map { it.id }, "kept once")
        assertTrue(library.movie(playlist, "mv_1b")!!.isFavorite)
    }
}
