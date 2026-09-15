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
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.live.LiveTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** With two sources, Live TV shows the chosen one and OK on the source item switches to the other (FR-SRC multi-source). */
@RunWith(AndroidJUnit4::class)
class MultiSourceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    /** Adds the synthetic provider twice, as "Source A" and "Source B", before the activity starts. */
    private val twoSources = object : ExternalResource() {
        override fun before() {
            val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
            runBlocking {
                val first = graph.addXtream("Source A", server, username, password)
                assertTrue("$first", first is AddSourceResult.Added)
                assertTrue(graph.addXtream("Source B", server, username, password) is AddSourceResult.Added)
                graph.selectSource((first as AddSourceResult.Added).playlistId)
            }
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(twoSources).around(rule)

    private fun press(vararg keyCodes: Int) {
        for (keyCode in keyCodes) {
            instrumentation.sendKeyDownUpSync(keyCode)
            rule.waitForIdle()
        }
    }

    private fun focusedTags() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun awaitFocus(tag: String) {
        runCatching { rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess } }
            .onFailure { throw AssertionError("expected focus on $tag, but focused: ${focusedTags()}", it) }
    }

    private fun awaitSourceName(name: String) {
        runCatching {
            rule.waitUntil(10_000) {
                rule.onAllNodes(hasTestTag(LiveTags.SWITCH_SOURCE).and(hasText(name, substring = true))).fetchSemanticsNodes().isNotEmpty()
            }
        }.onFailure { throw AssertionError("expected source item '$name'; focused: ${focusedTags()}", it) }
    }

    private fun channelTag(sourceName: String) = runBlocking {
        val source = graph.sources().single { it.name == sourceName }
        LiveTags.channel(graph.channels(source.playlistId, null).first().id)
    }

    @Test
    fun okOnTheSourceItemSwitchesLiveTvToTheOtherSource() {
        awaitSourceName("Source A")
        val channelA = channelTag("Source A")
        val channelB = channelTag("Source B")
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(channelA)).fetchSemanticsNodes().isNotEmpty() }

        repeat(
            5,
        ) { if (runCatching { rule.onNodeWithTag(LiveTags.SWITCH_SOURCE).assertIsFocused() }.isFailure) press(KeyEvent.KEYCODE_DPAD_UP) }
        awaitFocus(LiveTags.SWITCH_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        awaitSourceName("Source B")
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(channelB)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasTestTag(channelA)).fetchSemanticsNodes().isEmpty())
        assertEquals("Source B", runBlocking { graph.currentSource()?.name })
        awaitFocus(LiveTags.SWITCH_SOURCE)
    }
}
