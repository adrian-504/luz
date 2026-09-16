package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.onboarding.FormTags
import app.iptvplayer.tv.ui.onboarding.OnboardingTags
import app.iptvplayer.tv.ui.onboarding.SourceType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * An Xtream Codes playlist link typed into the M3U form is offered the Xtream login (IPTV_PROTOCOLS.md §3.4) — found on the
 * owner's provider, whose full playlist lists every movie and episode and took minutes to read on the Bbox TV.
 */
@RunWith(AndroidJUnit4::class)
class XtreamLinkFormTest {
    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(rule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

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

    @Test
    fun xtreamPlaylistLinkIsAddedWithTheXtreamLogin() {
        val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
        awaitFocus(OnboardingTags.ADD_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.sourceType(SourceType.XTREAM))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_URL))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(FormTags.URL)

        rule.onNodeWithTag(
            FormTags.URL,
        ).performTextInput("${server.trimEnd('/')}/get.php?username=$username&password=$password&type=m3u_plus&output=ts")
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(FormTags.XTREAM_LINK_HINT)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(FormTags.SUBMIT_FULL_PLAYLIST).assertExists()
        rule.onNodeWithTag(FormTags.SUBMIT).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.awaitKeyboardReleased()
        awaitFocus(FormTags.SUBMIT)
        rule.pressOkUntil {
            runBlocking { graph.sources() }.isNotEmpty() ||
                rule.onAllNodes(hasTestTag(FormTags.WORKING)).fetchSemanticsNodes().isNotEmpty()
        }

        runCatching { rule.waitUntil(20_000) { runBlocking { graph.sources() }.isNotEmpty() } }.onFailure {
            val present = listOf(FormTags.WORKING, FormTags.ERROR, FormTags.BUSY, FormTags.SUBMIT).filter { tag ->
                rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            throw AssertionError("source not added; on screen: $present, focused: ${focusedTags()}, adding=${graph.adding.value}", it)
        }
        assertEquals(PlaylistType.XTREAM, runBlocking { graph.sources() }.single().type)
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(LiveTags.GROUP_ALL)).fetchSemanticsNodes().isNotEmpty() }
    }
}
