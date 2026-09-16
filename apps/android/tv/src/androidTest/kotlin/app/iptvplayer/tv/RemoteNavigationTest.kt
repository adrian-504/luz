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
import app.iptvplayer.tv.ui.guide.GuideTags
import app.iptvplayer.tv.ui.library.LibraryTags
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.onboarding.FormTags
import app.iptvplayer.tv.ui.onboarding.OnboardingTags
import app.iptvplayer.tv.ui.onboarding.SourceType
import app.iptvplayer.tv.ui.settings.SettingsTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Remote (D-pad) navigation per docs/TESTING.md §3: entry focus, traversal, Back, no focus traps and focus restoration.
 * Keys are injected through the window like a real remote, so Back also exercises the activity's back dispatcher.
 */
@RunWith(AndroidJUnit4::class)
class RemoteNavigationTest {
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

    private fun focusedTag() = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TestTag) }

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
        // With no sources, Home is its welcome: one action, which is also where focus lands.
        awaitFocus(ShellTags.ADD_SOURCE)
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
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_FILE))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.FORM_BACK)

        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_FILE))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.FORM_BACK)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_FILE))

        // The M3U link form: its first field takes focus, and Back returns to the chosen source type.
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_URL))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(FormTags.URL)
        // Back leaves the form; if the system keyboard happens to be open, the first Back closes it (platform behavior).
        press(KeyEvent.KEYCODE_BACK)
        if (runCatching {
                rule.waitUntil(2_000) {
                    runCatching { rule.onNodeWithTag(OnboardingTags.sourceType(SourceType.M3U_URL)).assertIsFocused() }.isSuccess
                }
            }.isFailure
        ) {
            press(KeyEvent.KEYCODE_BACK)
        }
        awaitFocus(OnboardingTags.sourceType(SourceType.M3U_URL))

        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(OnboardingTags.ADD_SOURCE)
    }

    @Test
    fun contentToRailLandsOnSelectedSectionAndFocusIsRestored() {
        // Settings has a list to move around in without any source configured.
        enterMainShell()
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        repeat(Section.SETTINGS.ordinal) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(ShellTags.rail(Section.SETTINGS))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(SettingsTags.entry(SettingsTags.PROVIDERS))

        // Move off the entry the section opens on. The shell hands focus over a moment after the screen appears, so the
        // press is repeated until it lands rather than assumed.
        repeat(3) { if (focusedTag() != SettingsTags.ADD_SOURCE) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(SettingsTags.ADD_SOURCE)

        // Back jumps straight to the rail; Right returns to what had focus, not to the start of the screen.
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.SETTINGS))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(SettingsTags.ADD_SOURCE)
    }

    @Test
    fun everySectionIsReachableAndHasNoFocusTrap() {
        enterMainShell()
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        for (section in Section.entries) {
            if (section != Section.HOME) {
                walkTabsTo(
                    section,
                    ::focusedTag,
                    { key -> press(key) },
                ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
                awaitFocus(ShellTags.rail(section))
            }
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            // Without sources, Live TV, Favorites, the Guide, Movies and Series offer "Add a source"; Playlists lists no sources yet.
            // Without sources every section offers one way forward — add a provider — except Settings, which has its list.
            val entry = when (section) {
                Section.LIVE_TV -> LiveTags.emptyAddSource(favorites = false)
                Section.FAVORITES -> LiveTags.emptyAddSource(favorites = true)
                Section.GUIDE -> GuideTags.ADD_SOURCE
                Section.MOVIES, Section.SERIES -> LibraryTags.ADD_SOURCE
                Section.SEARCH -> SearchTags.ADD_SOURCE
                Section.HOME -> ShellTags.ADD_SOURCE
                Section.SETTINGS -> SettingsTags.entry(SettingsTags.PROVIDERS)
            }
            awaitFocus(entry)
            // No trap: the rail is always reachable from content, and returns to the selected section.
            press(KeyEvent.KEYCODE_DPAD_UP)
            awaitFocus(ShellTags.rail(section))
        }
    }

    @Test
    fun backMovesContentToRailToHomeThenLeavesTheApp() {
        enterMainShell()
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        walkTabsTo(
            Section.GUIDE,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(GuideTags.ADD_SOURCE)

        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.GUIDE))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        rule.onNodeWithTag(ShellTags.ADD_SOURCE).assertExists()

        // On Home with focus in the rail nothing intercepts Back, so the system returns to the TV launcher.
        // The final Back is not sent: leaving the activity would end the test before assertions.
        var interceptsBack = true
        rule.runOnUiThread { interceptsBack = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        assertFalse(interceptsBack)
    }

    @Test
    fun addSourceFromSettingsReturnsToTheSameButton() {
        enterMainShell()
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.HOME))
        repeat(Section.SETTINGS.ordinal) { press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(ShellTags.rail(Section.SETTINGS))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(SettingsTags.entry(SettingsTags.PROVIDERS))
        repeat(3) { if (focusedTag() != SettingsTags.ADD_SOURCE) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(SettingsTags.ADD_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.sourceType(SourceType.XTREAM))
        press(KeyEvent.KEYCODE_BACK)
        // Back from adding a source returns to the button it was started from.
        awaitFocus(SettingsTags.ADD_SOURCE)
    }
}
