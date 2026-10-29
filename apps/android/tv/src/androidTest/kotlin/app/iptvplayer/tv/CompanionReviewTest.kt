package app.iptvplayer.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.storage.FollowKind
import app.iptvplayer.testing.TestPanel
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/**
 * Keeps the phone companion up on the emulator with the test provider loaded, for a person (or the Design chat) to try the
 * phone page in a browser: run with `-e companionReviewMinutes 30`, forward the port (`adb forward tcp:8765 tcp:8765`)
 * and pair with the code it prints. Skipped without that argument, so ordinary runs are unaffected. Test data only.
 */
@RunWith(AndroidJUnit4::class)
class CompanionReviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    @Test
    fun keepsTheCompanionUpWithTheTestProvider() {
        val minutes = InstrumentationRegistry.getArguments().getString("companionReviewMinutes")?.toIntOrNull()
        assumeTrue(minutes != null)
        runBlocking {
            if (graph.sources().isEmpty()) {
                val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
                val added = graph.addXtream("Test provider", server, username, password)
                assertTrue("$added", added is AddSourceResult.Added)
                val playlist = (added as AddSourceResult.Added).playlistId
                val deadline = System.nanoTime() + 5.minutes.inWholeNanoseconds
                while (listOf(ImportUnit.LIVE, ImportUnit.MOVIES, ImportUnit.SERIES)
                        .any { graph.libraryState(playlist, it)?.status != ImportStatus.PUBLISHED }
                ) {
                    check(System.nanoTime() < deadline) { "test provider import did not finish" }
                    Thread.sleep(200)
                }
            }
        }
        // With `-e companionReviewSport true`: the fake football-data.org, its key, and a team and league followed, for
        // the phone's Matches tab.
        if (InstrumentationRegistry.getArguments().getString("companionReviewSport") != null) {
            DeveloperStreams.useTestSport = true
            runBlocking { assertTrue(graph.setSportToken(TestPanel.FOOTBALL_TOKEN) == null) }
            graph.follow(FollowKind.TEAM, "9065", "Example City FC")
            graph.follow(FollowKind.COMPETITION, "EXL", "Example League")
        }
        graph.companion.setOn(true)
        val until = System.nanoTime() + minutes!!.minutes.inWholeNanoseconds
        while (System.nanoTime() < until) {
            // The code turns over as it is used or expires: printed again every half minute.
            println("companion-review: pairing code ${graph.companion.pairing.code()}")
            Thread.sleep(30_000)
        }
    }
}
