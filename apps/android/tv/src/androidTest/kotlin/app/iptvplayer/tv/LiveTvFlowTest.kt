package app.iptvplayer.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.guide.GuideTags
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.onboarding.FormTags
import app.iptvplayer.tv.ui.onboarding.OnboardingTags
import app.iptvplayer.tv.ui.onboarding.SourceType
import app.iptvplayer.tv.ui.player.PlayerTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import app.iptvplayer.tv.ui.theme.LuzMenuTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Phase 7 end to end with the remote: add the synthetic Xtream provider by typing its login, browse Live TV, play and zap
 * channels, keep a favorite, see the guide, and remove the source.
 */
@RunWith(AndroidJUnit4::class)
class LiveTvFlowTest {
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

    /** A long press of OK as a remote sends it: repeated key-down events with the long-press flag, then key-up. */
    private fun longPressOk() {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        Thread.sleep(700)
        instrumentation.sendKeySync(
            KeyEvent.changeTimeRepeat(
                KeyEvent(
                    down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER, 1, 0, 0, 0, KeyEvent.FLAG_LONG_PRESS,
                ),
                SystemClock.uptimeMillis(),
                1,
            ),
        )
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        rule.waitForIdle()
        // A person lets go and looks before choosing; a menu ignores OK until the button has been quiet (LuzMenu).
        Thread.sleep(MENU_SETTLE_MS)
    }

    private fun focusedTags() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun focusedTag(): String? = focusedTags().singleOrNull()

    private fun awaitFocus(tag: String, timeout: Long = 10_000) {
        runCatching { rule.waitUntil(timeout) { runCatching { rule.onNodeWithTag(tag).assertIsFocused() }.isSuccess } }
            .onFailure { throw AssertionError("expected focus on $tag, but focused: ${focusedTags()}", it) }
    }

    private fun awaitExists(tag: String, text: String? = null, timeout: Long = 15_000) {
        val matcher = if (text == null) hasTestTag(tag) else hasTestTag(tag).and(hasText(text, substring = true))
        // Merged tree: a clickable cell's text belongs to the cell's own node.
        runCatching { rule.waitUntil(timeout) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("expected $tag${text?.let { " with '$it'" }.orEmpty()}; focused: ${focusedTags()}", it) }
    }

    @Test
    fun addProviderBrowsePlayZapFavoriteGuideAndRemove() {
        val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!

        // Onboarding: Welcome → Add a source → Xtream Codes login, typed into the form.
        awaitFocus(OnboardingTags.ADD_SOURCE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(OnboardingTags.sourceType(SourceType.XTREAM))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(FormTags.SERVER)
        rule.onNodeWithTag(FormTags.SERVER).performTextInput(server)
        rule.onNodeWithTag(FormTags.USERNAME).performTextInput(username)
        rule.onNodeWithTag(FormTags.PASSWORD_FIELD).performTextInput(password)
        rule.onNodeWithTag(FormTags.SUBMIT).requestFocusCompat()
        rule.awaitKeyboardReleased()
        rule.pressOkUntil { rule.onAllNodes(hasTestTag(FormTags.WORKING), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(20_000) {
            val error = rule.onAllNodes(hasTestTag(FormTags.ERROR), useUnmergedTree = true).fetchSemanticsNodes()
            if (error.isNotEmpty()) {
                throw AssertionError(
                    "adding the source failed: ${error.first().config.getOrNull(SemanticsProperties.Text)}",
                )
            }
            rule.onAllNodes(hasTestTag(FormTags.SUBMIT), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }

        // Live TV opens with the imported channels; the guide arrives in the background.
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        awaitExists(LiveTags.channel(channelId("Test News HD")))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LiveTags.channel(channelId("Test News HD")))
        // "Test News 2" has no XMLTV guide: its now/next comes from the provider's per-channel guide once it is on screen.
        awaitExists(LiveTags.channel(channelId("Test News 2")), "Test News 2 short guide", timeout = 20_000)

        // A long press opens the channel's menu (ADR-0032); favourite from there, and focus returns to the row.
        longPressOk()
        awaitExists(LuzMenuTags.MENU)
        awaitFocus(LuzMenuTags.item("play"))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(LuzMenuTags.item("favorite"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(5_000) { runBlocking { graph.favoriteChannels(playlist()).map { it.name } } == listOf("Test News HD") }
        awaitFocus(LiveTags.channel(channelId("Test News HD")))

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(20_000) {
            runCatching { rule.onNodeWithTag(PlayerTags.STATE).assertExistsWithText(stateText(PlaybackState.PLAYING)) }.isSuccess
        }

        // Hide the overlay, zap forward twice quickly with Right (ADR-0036): the bar follows at once, one stream is opened
        // for channel 3.
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        // The banner is transient (and the test clock runs fast), so the result is read from the overlay title.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitExists(PlayerTags.TITLE, "Test Sports")
        rule.waitUntil(20_000) {
            runCatching { rule.onNodeWithTag(PlayerTags.STATE).assertExistsWithText(stateText(PlaybackState.PLAYING)) }.isSuccess
        }

        // The last-channel key returns to the channel watched before; it was prepared ahead while Test Sports played. The
        // state text alone can still read "Playing" from the channel before, so wait for the zap to settle on Test Sports —
        // its programme appears under the title — which is when the channels around it are prepared.
        awaitExists(PlayerTags.PROGRAMME, "Test Sports programme")
        // The channels around it are prepared right after, one at a time, and the channel watched before comes third
        // (PreparationWindow). Nothing on screen shows that work finishing, so give it a moment — on the test clock for
        // the player's own delays, and in real time for the lookups it makes.
        rule.mainClock.advanceTimeBy(PREPARE_GRACE_MS)
        Thread.sleep(PREPARE_GRACE_MS)
        rule.waitForIdle()
        press(KeyEvent.KEYCODE_LAST_CHANNEL)
        awaitExists(PlayerTags.TITLE, "Test News HD")
        rule.waitUntil(20_000) {
            runCatching { rule.onNodeWithTag(PlayerTags.STATE).assertExistsWithText(stateText(PlaybackState.PLAYING)) }.isSuccess
        }
        // Diagnostics: the controls are up (the state reads "Playing" only there); Info, last in their row, opens the panel
        // on its Info tab, and Advanced is under it.
        repeat(
            8,
        ) { if (runCatching { rule.onNodeWithTag(PlayerTags.INFO).assertIsFocused() }.isFailure) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(PlayerTags.INFO)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(PlayerTags.tab("INFO"))
        repeat(3) {
            if (runCatching {
                    rule.onNodeWithTag(
                        PlayerTags.DIAGNOSTICS_TOGGLE,
                    ).assertIsFocused()
                }.isFailure
            ) {
                press(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        awaitFocus(PlayerTags.DIAGNOSTICS_TOGGLE)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("prepared ahead", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK)

        // Back in Live TV; the Guide shows programmes from the provider's XMLTV.
        awaitFocus(LiveTags.channel(channelId("Test News HD")))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.LIVE_TV))
        walkTabsTo(
            Section.GUIDE,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(GuideTags.FIRST_CELL, timeout = 20_000)
        awaitExists(GuideTags.FIRST_CELL, "Test News HD programme", timeout = 20_000)

        // Removing the source empties Live TV.
        runBlocking { graph.delete(playlist()) }
        assertTrue(runBlocking { graph.sources() }.isEmpty())
    }

    private fun playlist() = runBlocking { graph.sources().single().playlistId }

    private fun channelId(name: String) = runBlocking { graph.channels(playlist(), null).single { it.name == name }.id }

    private fun stateText(state: PlaybackState) = rule.activity.getString(
        when (state) {
            PlaybackState.PLAYING -> R.string.player_state_playing
            else -> R.string.player_state_preparing
        },
    )

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsWithText(text: String) {
        val node = fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertEquals(text, texts.joinToString(""))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.requestFocusCompat() {
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
        rule.waitForIdle()
        assertIsFocused()
    }
}

/** Time for the player to prepare the channels around the one it settled on (see the last-channel step). */
private const val PREPARE_GRACE_MS = 1_500L

private const val MENU_SETTLE_MS = 300L
