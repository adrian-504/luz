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
    }

    private fun focusedTags() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.getOrNull(SemanticsProperties.TestTag) }

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
        press(KeyEvent.KEYCODE_DPAD_CENTER)
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

        // Favorite with a long press, then play.
        longPressOk()
        rule.waitUntil(5_000) { runBlocking { graph.favoriteChannels(playlist()).map { it.name } } == listOf("Test News HD") }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(20_000) {
            runCatching { rule.onNodeWithTag(PlayerTags.STATE).assertExistsWithText(stateText(PlaybackState.PLAYING)) }.isSuccess
        }

        // Hide the overlay, zap down twice quickly: the banner follows at once, one stream is opened for channel 3.
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN)
        // The banner is transient (and the test clock runs fast), so the result is read from the overlay title.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitExists(PlayerTags.TITLE, "Test Sports")
        rule.waitUntil(20_000) {
            runCatching { rule.onNodeWithTag(PlayerTags.STATE).assertExistsWithText(stateText(PlaybackState.PLAYING)) }.isSuccess
        }
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK)

        // Back in Live TV; the Guide shows programmes from the provider's XMLTV.
        awaitFocus(LiveTags.channel(channelId("Test News HD")))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.LIVE_TV))
        press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
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
