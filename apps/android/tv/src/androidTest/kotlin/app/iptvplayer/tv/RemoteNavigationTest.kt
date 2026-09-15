package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.tv.ui.onboarding.OnboardingTags
import app.iptvplayer.tv.ui.onboarding.SourceType
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remote (D-pad) navigation per docs/TESTING.md §3: entry focus, traversal, Back, no focus traps and focus restoration.
 * Keys are injected through the window like a real remote, so Back also exercises the activity's back dispatcher.
 */
@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun press(vararg keyCodes: Int) {
        for (keyCode in keyCodes) {
            instrumentation.sendKeyDownUpSync(keyCode)
            rule.waitForIdle()
        }
    }

    private fun awaitFocus(tag: String) {
        val focused = runCatching {
            rule.waitUntil(timeoutMillis = 5_000) { runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess }
        }
        if (focused.isFailure) {
            val actual = rule.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }
            throw AssertionError("expected focus on $tag, but focused: $actual", focused.exceptionOrNull())
        }
    }

    private fun enterMainShell() {
        awaitFocus(OnboardingTags.ADD_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(OnboardingTags.EXPLORE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(ShellTags.item(Section.HOME, 0))
    }

    @Test
    fun onboardingEntryTraversalBackAndRestoration() {
        awaitFocus(OnboardingTags.ADD_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(OnboardingTags.EXPLORE)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocus(OnboardingTags.ADD_SOURCE)

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.sourceType(SourceType.XTREAM))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_URL))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.FORM_BACK)

        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_URL))
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.FORM_BACK)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_FILE))

        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(OnboardingTags.ADD_SOURCE)
    }

    @Test
    fun contentToRailLandsOnSelectedSectionAndRowFocusIsRestored() {
        enterMainShell()
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(ShellTags.item(Section.HOME, 2))

        // Back jumps straight to the rail; Right returns to the card that had focus.
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(ShellTags.item(Section.HOME, 2))

        // Walking left leaves the row from its first card, so that card is the one restored.
        press(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
        awaitFocus(ShellTags.rail(Section.HOME))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(ShellTags.item(Section.HOME, 0))
    }

    @Test
    fun everySectionIsReachableAndHasNoFocusTrap() {
        enterMainShell()
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        for (section in Section.entries) {
            if (section != Section.HOME) {
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocus(ShellTags.rail(section))
            }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            val first = if (section == Section.PLAYLISTS) ShellTags.ADD_SOURCE else ShellTags.item(section, 0)
            awaitFocus(first)
            if (section == Section.PLAYLISTS) {
                press(KeyEvent.KEYCODE_DPAD_DOWN)
                awaitFocus(ShellTags.item(section, 0))
            }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus(ShellTags.item(section, 1))
            // No trap: the rail is always reachable from content, and returns to the selected section.
            press(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_LEFT)
            awaitFocus(ShellTags.rail(section))
        }
    }

    @Test
    fun backMovesContentToRailToHomeThenLeavesTheApp() {
        enterMainShell()
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(ShellTags.item(Section.GUIDE, 0))

        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.GUIDE))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        rule.onNodeWithTag(ShellTags.item(Section.HOME, 0)).assertExists()

        // On Home with focus in the rail nothing intercepts Back, so the system returns to the TV launcher.
        // The final Back is not sent: leaving the activity would end the test before assertions.
        var interceptsBack = true
        rule.runOnUiThread { interceptsBack = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        assertFalse(interceptsBack)
    }

    @Test
    fun addSourceFromPlaylistsReturnsToTheSameButton() {
        enterMainShell()
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        repeat(Section.PLAYLISTS.ordinal) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(ShellTags.rail(Section.PLAYLISTS))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(ShellTags.ADD_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.sourceType(SourceType.XTREAM))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.ADD_SOURCE)
    }
}
