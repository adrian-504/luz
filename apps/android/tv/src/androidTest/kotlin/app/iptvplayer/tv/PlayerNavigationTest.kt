package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.tv.ui.onboarding.OnboardingTags
import app.iptvplayer.tv.ui.player.PlayerTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** Player remote behavior (DESIGN_SYSTEM.md §6) with the debug build's synthetic streams. */
@RunWith(AndroidJUnit4::class)
class PlayerNavigationTest {
    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(rule)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

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

    private fun awaitText(tag: String, text: String) {
        runCatching { rule.waitUntil(20_000) { runCatching { rule.onNodeWithTag(tag).assertTextEquals(text) }.isSuccess } }
            .onFailure { throw AssertionError("expected '$text' in $tag", it) }
    }

    private fun awaitGone(tag: String) = rule.waitUntil(5_000) {
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
    }

    private fun string(id: Int) = rule.activity.getString(id)

    private fun openDeveloperStream(id: String) {
        awaitFocus(OnboardingTags.ADD_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(ShellTags.item(Section.HOME, 0))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        repeat(Section.SETTINGS.ordinal) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(ShellTags.developerStream("hls-live"))
        val order = listOf("hls-live", "ts-live", "hls-vod", "mp4-vod", "not-found", "unsupported")
        repeat(order.indexOf(id)) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(ShellTags.developerStream(id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    @Test
    fun overlayPauseResumeAndBackOutOfThePlayer() {
        openDeveloperStream("hls-vod")
        awaitText(PlayerTags.STATE, string(R.string.player_state_playing))
        awaitFocus(PlayerTags.PLAY_PAUSE)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitText(PlayerTags.STATE, string(R.string.player_state_paused))

        press(KeyEvent.KEYCODE_BACK)
        awaitGone(PlayerTags.OVERLAY)
        awaitFocus(PlayerTags.ROOT)

        // OK shows the overlay without also pressing the button that receives focus.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(PlayerTags.PLAY_PAUSE)
        rule.onNodeWithTag(PlayerTags.STATE).assertTextEquals(string(R.string.player_state_paused))

        press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        awaitText(PlayerTags.STATE, string(R.string.player_state_playing))

        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(5_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasTestTag(PlayerTags.DIAGNOSTICS_PANEL)).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_BACK)
        awaitGone(PlayerTags.DIAGNOSTICS_PANEL)
        press(KeyEvent.KEYCODE_BACK)
        awaitGone(PlayerTags.OVERLAY)
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.developerStream("hls-vod"))
    }

    @Test
    fun errorScreenOffersRetryAndBackLeaves() {
        openDeveloperStream("not-found")
        awaitFocus(PlayerTags.RETRY)
        rule.onNodeWithTag(PlayerTags.ERROR_MESSAGE).assertTextEquals(string(R.string.playback_error_http_not_found))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(PlayerTags.RETRY)
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.developerStream("not-found"))
    }
}
