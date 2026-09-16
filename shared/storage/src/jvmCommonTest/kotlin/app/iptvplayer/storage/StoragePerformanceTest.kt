package app.iptvplayer.storage

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.MovieId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.model.Channel
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentKind
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ExternalIds
import app.iptvplayer.domain.model.IdentityHints
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.db.IptvDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * The query budgets of docs/PERFORMANCE.md §1 on a library the size of a large provider: guide window and now/next queries
 * and search must stay under 50 ms (P95). This runs on the JVM and, as the real gate, on devices — the owner's Bbox TV is the
 * reference low-end device. It builds its own data, so it does not depend on a provider.
 */
class StoragePerformanceTest {
    private val file: File = File.createTempFile("storage-performance", ".db").also { it.delete() }
    private val driver = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    private val now = Instant.parse("2026-09-16T12:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private val content = ContentStore(driver, clock)
    private val library = LibraryStore(content, clock)
    private val epg = EpgStore(driver)
    private val playlist = PlaylistId("perf-playlist")
    private val epgSource = EpgSourceId("perf-epg")

    @AfterTest
    fun cleanUp() {
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun buildLibrary() {
        content.addSource(
            playlist,
            ProviderId("perf-provider"),
            "Performance",
            PlaylistType.XTREAM,
            "http://panel.example.com:8080",
            UrlTemplate("http://panel.example.com:8080"),
            CredentialRef("cred"),
            TransportSecurity.CLEARTEXT,
        )
        val channels = content.beginLiveSnapshot(playlist, batchSize = 1_000)
        repeat(GROUPS) { group ->
            channels.group(ChannelGroup(GroupId("g$group"), playlist, ContentKind.LIVE, "Group $group", group, null))
        }
        repeat(CHANNELS) { index ->
            val id = ChannelId("ch$index")
            channels.channel(
                Channel(
                    id, playlist, listOf(GroupId("g${index % GROUPS}")), "Channel $index Sports", (index + 1), null,
                    "ch$index.example", null, emptyList(), emptyList(), null, listOf(MediaSourceId("ms_ch$index")), null,
                    emptyMap(), IdentityHints(null, null, "channel $index"),
                ),
                source(ContentType.CHANNEL, "ch$index", XtreamStreamKind.LIVE),
                null,
            )
        }
        channels.publish()

        val movies = library.beginSnapshot(playlist, ImportUnit.MOVIES, batchSize = 1_000)
        repeat(MOVIE_GROUPS) { group ->
            movies.group(ChannelGroup(GroupId("mg$group"), playlist, ContentKind.MOVIE, "Movies $group", group, null))
        }
        repeat(MOVIES) { index ->
            movies.movie(
                Movie(
                    MovieId("mv$index"), playlist, listOf(GroupId("mg${index % MOVIE_GROUPS}")), "Movie $index Adventure", 2020,
                    100.minutes, null, listOf("Action"), null, null, null, null, listOf(MediaSourceId("ms_mv$index")), "$index,",
                    ExternalIds(), null,
                ),
                source(ContentType.MOVIE, "mv$index", XtreamStreamKind.MOVIE),
                null,
            )
        }
        movies.publish()

        val writer = epg.beginSnapshot(epgSource, snapshot = 1, batchSize = 2_000)
        repeat(GUIDE_CHANNELS) { channel ->
            repeat(PROGRAMMES_PER_CHANNEL) { slot ->
                val start = now - 12.hours + (slot * 30).minutes
                writer.add(
                    ProgramRow(
                        ProgramId("p$channel-$slot"),
                        "ch$channel",
                        start,
                        start + 30.minutes,
                        "Programme $slot on channel $channel",
                        null,
                        null,
                    ),
                )
            }
        }
        writer.finish()
        epg.activate(epgSource, 1)
        epg.replaceLinks(
            playlist.value,
            (0 until GUIDE_CHANNELS).map { ChannelEpgLinkRow("ch$it", epgSource.value, "ch$it", "EXACT_ID", 100) },
        )
    }

    private fun source(type: ContentType, id: String, kind: XtreamStreamKind) = MediaSource(
        MediaSourceId("ms_$id"),
        ContentRef(type, id),
        MediaLocator.XtreamStream(kind, id.filter { it.isDigit() }.ifEmpty { "1" }, "ts"),
        StreamProtocol.PROGRESSIVE_TS,
        MediaHeaders(),
        null,
        null,
        0,
    )

    /** One query's cold first run and the P95 of [runs] runs after it, in milliseconds. */
    private data class Timing(val cold: Double, val p95: Double) {
        override fun toString(): String = "${p95}ms(cold ${cold}ms)"
    }

    private fun measure(block: () -> Unit): Double {
        val mark = TimeSource.Monotonic.markNow()
        block()
        return mark.elapsedNow().inWholeMicroseconds / 1000.0
    }

    /**
     * Times [block]. The first run is reported separately: it pays for pages this device has not read yet, and a single slow
     * first read would otherwise be the whole P95 of a short series.
     */
    private fun timed(runs: Int = 20, block: () -> Unit): Timing {
        val cold = measure(block)
        val samples = ArrayList<Double>(runs)
        repeat(runs) { samples += measure(block) }
        samples.sort()
        val index = ((samples.size * 95 + 99) / 100) - 1
        return Timing(cold, samples[index.coerceIn(0, samples.size - 1)])
    }

    @Test
    fun guideAndSearchQueriesStayWithinTheirBudgets() {
        val built = TimeSource.Monotonic.markNow()
        buildLibrary()
        val buildMs = built.elapsedNow().inWholeMilliseconds

        val visible = (0 until 20).map { "ch$it" }
        val nowNext = timed { epg.nowNext(playlist.value, visible, now) }
        val window = timed { epg.programmes(playlist.value, visible.take(10), now, now + 3.hours) }
        val channelPage = timed { content.channels(playlist, "g3") }
        val moviePage = timed { library.movies(playlist, null, limit = 120, offset = 2_000) }
        val searchChannels = timed { content.searchChannels(playlist, "Sports", 30) }
        val searchMovies = timed { library.searchMovies(playlist, "Adventure", 30) }
        val fullList = timed(runs = 5) { content.channels(playlist) }

        println(
            "storage-performance: build=${buildMs}ms nowNext(20)=$nowNext window(10)=$window " +
                "channelGroup=$channelPage moviePage=$moviePage searchChannels=$searchChannels " +
                "searchMovies=$searchMovies allChannels($CHANNELS)=$fullList",
        )

        // docs/PERFORMANCE.md §1: guide window and search P95 <= 50 ms. The list pages a screen at a time, so they share it.
        assertTrue(nowNext.p95 <= BUDGET_MS, "now/next $nowNext")
        assertTrue(window.p95 <= BUDGET_MS, "guide window $window")
        assertTrue(channelPage.p95 <= BUDGET_MS, "channel group $channelPage")
        assertTrue(moviePage.p95 <= BUDGET_MS, "movie page $moviePage")
        assertTrue(searchChannels.p95 <= BUDGET_MS, "channel search $searchChannels")
        assertTrue(searchMovies.p95 <= BUDGET_MS, "movie search $searchMovies")
        // A first read of a page this device has not touched yet is slower, but must not be a visible wait (FR-PERF-002).
        listOf(nowNext, window, channelPage, moviePage, searchChannels, searchMovies).forEach {
            assertTrue(it.cold <= COLD_BUDGET_MS, "first run $it")
        }
    }

    private companion object {
        const val CHANNELS = 10_000
        const val GROUPS = 120
        const val MOVIES = 20_000
        const val MOVIE_GROUPS = 40
        const val GUIDE_CHANNELS = 600
        const val PROGRAMMES_PER_CHANNEL = 100
        const val BUDGET_MS = 50.0
        const val COLD_BUDGET_MS = 300.0
    }
}
