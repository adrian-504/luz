package app.iptvplayer.tv

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.player.PlayerTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Live television in the player (ADR-0036): the live bar, the channel list over the picture on Up, and the panel on Down.
 *
 * Run with `-e screenshots true` to also save what the screen looks like at each step, into the app's external files
 * (`shots/`), for looking at the design on a device without driving it by hand.
 */
@RunWith(AndroidJUnit4::class)
class LivePlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph
    private val screenshots = InstrumentationRegistry.getArguments().getString("screenshots") == "true"

    private val source = object : ExternalResource() {
        override fun before() {
            val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
            runBlocking { assertTrue(graph.addXtream("Live player test", server, username, password) is AddSourceResult.Added) }
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(source).around(rule)

    private val playlist get() = runBlocking { graph.sources().single().playlistId }

    private fun press(vararg keyCodes: Int) {
        for (keyCode in keyCodes) {
            instrumentation.sendKeyDownUpSync(keyCode)
            rule.waitForIdle()
        }
    }

    private fun focusedTag() = rule.onAllNodes(isFocused()).fetchSemanticsNodes().firstNotNullOfOrNull {
        it.config.getOrNull(SemanticsProperties.TestTag)
    }

    private fun awaitFocus(tag: String, timeout: Long = 10_000) {
        runCatching { rule.waitUntil(timeout) { rule.onAllNodes(hasTestTag(tag).and(isFocused())).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("expected focus on $tag, but focused: ${focusedTag()}", it) }
    }

    private fun awaitExists(tag: String, text: String? = null, timeout: Long = 20_000) {
        val matcher = if (text == null) hasTestTag(tag) else hasTestTag(tag).and(hasText(text, substring = true))
        runCatching { rule.waitUntil(timeout) { rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("expected $tag ${text.orEmpty()} on screen; focused: ${focusedTag()}", it) }
    }

    private fun awaitGone(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    private fun shot(name: String) {
        if (!screenshots) return
        rule.waitForIdle()
        Thread.sleep(SHOT_SETTLE_MS)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun theChannelListComesUpOverThePictureAndThePanelComesDown() {
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        val channels = runBlocking { graph.channels(playlist, null) }
        val first = channels.first()
        val second = channels[1]
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LiveTags.channel(first.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        // OK on a channel plays it with the controls up: the live bar names the channel and says what is on.
        awaitFocus(PlayerTags.PLAY_PAUSE, timeout = 20_000)
        awaitExists(PlayerTags.TITLE, first.name)
        awaitExists(PlayerTags.PROGRAMME, "programme")
        shot("live-controls")

        // Up, with the controls away: the list the channel came from, over the left of the picture, on the channel playing.
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(PlayerTags.ROOT)
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitExists(PlayerTags.CHANNEL_LIST)
        awaitFocus(PlayerTags.channelRow(first.id.value))
        shot("live-channel-list")

        // Down to the next channel and OK plays it; the list goes.
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(PlayerTags.channelRow(second.id.value))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitGone(PlayerTags.CHANNEL_LIST)
        awaitExists(PlayerTags.TITLE, second.name)

        // Down brings the panel with what is on; Back puts it away.
        rule.waitUntil(10_000) { focusedTag() == PlayerTags.ROOT }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(PlayerTags.TRACK_PANEL)
        awaitFocus(PlayerTags.tab("INFO"))
        shot("live-info-panel")
        press(KeyEvent.KEYCODE_BACK)
        awaitGone(PlayerTags.TRACK_PANEL)
    }

    private companion object {
        const val SHOT_SETTLE_MS = 800L
    }
}
