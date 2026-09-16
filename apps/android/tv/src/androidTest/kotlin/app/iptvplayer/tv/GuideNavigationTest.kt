package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.guide.GuideTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Guide remote behavior (EPG.md §5) with the synthetic provider's guide (30-minute programmes around now). */
@RunWith(AndroidJUnit4::class)
class GuideNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    private val provider = object : ExternalResource() {
        override fun before() {
            val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
            runBlocking {
                val added = graph.addXtream("Guide test", server, username, password)
                assertTrue("$added", added is AddSourceResult.Added)
                val playlist = (added as AddSourceResult.Added).playlistId
                // Guide refresh runs in the background after adding; wait until programmes are stored.
                val deadline = System.nanoTime() + 20.seconds()
                while (graph.nowNext(playlist, graph.channels(playlist, null).map { it.id }, Clock.System.now()).isEmpty()) {
                    check(System.nanoTime() < deadline) { "guide not imported" }
                    Thread.sleep(100)
                }
            }
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(provider).around(rule)

    private fun Int.seconds() = this * 1_000_000_000L

    private fun press(vararg keyCodes: Int) {
        for (keyCode in keyCodes) {
            instrumentation.sendKeyDownUpSync(keyCode)
            rule.waitForIdle()
        }
    }

    private fun focusedTag() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstNotNullOfOrNull {
        it.config.getOrNull(SemanticsProperties.TestTag)
    }

    private fun awaitFocus(tag: String) {
        runCatching { rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess } }
            .onFailure { throw AssertionError("expected focus on $tag, but focused: ${focusedTag()}", it) }
    }

    private fun windowStartText(): String? = rule.onAllNodes(hasTestTag(GuideTags.WINDOW_START)).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString()

    private fun channels(): List<ChannelRow> = runBlocking { graph.channels(graph.sources().single().playlistId, null) }

    private fun programmeAt(channel: ChannelRow, time: Instant) = runBlocking {
        graph.programmes(graph.sources().single().playlistId, listOf(channel.id), time - 1.hours, time + 1.days)[channel.id.value]
            .orEmpty()
            .single { it.start <= time && time < it.end }
    }

    private fun awaitDetails(title: String) {
        runCatching {
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag(GuideTags.DETAILS).and(hasText(title, substring = true))).fetchSemanticsNodes().isNotEmpty()
            }
        }.onFailure { throw AssertionError("expected '$title' in the programme details; focused: ${focusedTag()}", it) }
    }

    @Test
    fun upDownKeepTheTimeRightPagesLaterAndNowReturns() {
        // Live TV opens first; Back reaches the bar across the top, where Guide is the next section along.
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.LIVE_TV))
        walkTabsTo(
            Section.GUIDE,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(ShellTags.rail(Section.GUIDE))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(GuideTags.FIRST_CELL)

        val rows = channels()
        val start = Clock.System.now()
        awaitDetails(programmeAt(rows[0], start).title)
        val initialWindow = windowStartText()

        // Down twice. Row 1 has no XMLTV guide: its programmes come from the provider's per-channel guide (get_short_epg).
        // Each move focuses the programme airing at the focused time.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val shortGuide = programmeAt(rows[1], start)
        assertTrue(shortGuide.title, shortGuide.title.contains("short guide"))
        awaitFocus(GuideTags.cell(rows[1].id, shortGuide.start))
        awaitDetails(shortGuide.title)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(GuideTags.cell(rows[2].id, programmeAt(rows[2], start).start))

        // Right moves through programmes; past the last visible one the window moves 90 minutes later.
        repeat(12) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        rule.waitUntil(5_000) { windowStartText() != initialWindow }
        assertTrue("focus stays in the guide row: ${focusedTag()}", focusedTag()?.startsWith("guide-${rows[2].id.value}-") == true)

        // Up from the first row reaches "Now", which returns to the present.
        pressUntilFocused(
            GuideTags.NOW,
            KeyEvent.KEYCODE_DPAD_UP,
            attempts = 3,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(GuideTags.NOW)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(5_000) { windowStartText() == initialWindow }
    }
}
