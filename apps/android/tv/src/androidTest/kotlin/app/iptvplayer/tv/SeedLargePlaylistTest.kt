package app.iptvplayer.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.app.IptvApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/**
 * Leaves a large synthetic playlist installed on the device, so launch, scrolling and memory can be measured against a
 * realistic library instead of an empty one (PERFORMANCE.md §3) — without anyone's real provider or credentials.
 *
 * Serve `tooling/fixtures/generated` from the development machine and run, with this machine's address on the LAN:
 * ```
 * ./gradlew :apps:android:tv:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=app.iptvplayer.tv.SeedLargePlaylistTest \
 *   -Pandroid.testInstrumentationRunnerArguments.seedPlaylistUrl=http://192.168.1.2:8000/large-10k.m3u
 * ```
 * Add `seedGuideUrl` (for example the fixture `large-100k.xml.gz`) to load a guide for the same source.
 * Without that argument it is skipped, so ordinary test runs are unaffected. Unlike every other device test it keeps
 * what it imported: that is the point. The fixture's stream URLs are unreachable by design (`example.com`), so the
 * seeded library is for browsing measurements, not playback.
 */
@RunWith(AndroidJUnit4::class)
class SeedLargePlaylistTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    @Test
    fun importsTheLargePlaylistAndLeavesItInstalled() {
        val url = InstrumentationRegistry.getArguments().getString("seedPlaylistUrl")
        val guideUrl = InstrumentationRegistry.getArguments().getString("seedGuideUrl")
        if (url == null && guideUrl == null) {
            // Nothing to seed. A plain return, not an assumption: this runner reports a violated assumption as a failure.
            println("seed-large-playlist: skipped (pass seedPlaylistUrl to seed a device)")
            return
        }

        runBlocking {
            // Re-runnable: a device that already holds the fixture keeps it instead of importing a second copy.
            val existing = graph.sources().firstOrNull()
            val playlist = if (existing != null) {
                existing.playlistId
            } else {
                val added = graph.addM3u("Performance fixture", url!!)
                assertTrue("$added", added is AddSourceResult.Added)
                (added as AddSourceResult.Added).playlistId
            }
            val deadline = System.nanoTime() + 10.minutes.inWholeNanoseconds
            while (graph.libraryState(playlist, ImportUnit.LIVE)?.status != ImportStatus.PUBLISHED) {
                check(System.nanoTime() < deadline) { "import did not finish: ${graph.libraryState(playlist, ImportUnit.LIVE)}" }
                Thread.sleep(500)
            }
            val channels = graph.channels(playlist, null).size
            println("seed-large-playlist: imported $channels channels")
            assertTrue("imported $channels channels", channels > 1_000)

            // Optional second argument: a guide to go with it, for measuring the guide screen with a real amount of data.
            guideUrl?.let { guide ->
                val refused = graph.setGuideLink(playlist, guide)
                assertTrue("guide link refused: $refused", refused == null)
                val guideDeadline = System.nanoTime() + 10.minutes.inWholeNanoseconds
                while (graph.guideState(playlist)?.status != ImportStatus.PUBLISHED) {
                    check(System.nanoTime() < guideDeadline) { "guide import did not finish: ${graph.guideState(playlist)}" }
                    Thread.sleep(500)
                }
                println("seed-large-playlist: guide programmes ${graph.guideState(playlist)?.itemCount}")
            }
        }
    }
}
