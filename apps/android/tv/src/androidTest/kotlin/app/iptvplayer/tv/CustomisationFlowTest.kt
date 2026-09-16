package app.iptvplayer.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.settings.SettingsTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import app.iptvplayer.tv.ui.theme.LuzMenuTags
import app.iptvplayer.tv.ui.theme.LuzPromptTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Hiding and renaming with the remote (FR-PLM-001): what the viewer hides leaves the lists, what they rename shows their
 * own name, and Settings offers everything hidden back. The provider's own data is untouched underneath — the storage
 * tests prove that part; this one proves the remote reaches it.
 */
@RunWith(AndroidJUnit4::class)
class CustomisationFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    private val source = object : ExternalResource() {
        override fun before() {
            val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
            runBlocking { assertTrue(graph.addXtream("Customisation test", server, username, password) is AddSourceResult.Added) }
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

    /** Holding OK: the remote sends repeats, and the first of them is the long press (ADR-0031). */
    private fun longPressOk() {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        Thread.sleep(700)
        instrumentation.sendKeySync(
            KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1),
        )
        Thread.sleep(100)
        instrumentation.sendKeySync(KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        rule.waitForIdle()
    }

    private fun focusedTag() = rule.onAllNodes(isFocused()).fetchSemanticsNodes()
        .firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun awaitFocus(tag: String, timeout: Long = 10_000) {
        runCatching { rule.waitUntil(timeout) { rule.onAllNodes(hasTestTag(tag).and(isFocused())).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("expected focus on $tag, but focused: ${focusedTag()}", it) }
    }

    private fun awaitExists(tag: String, timeout: Long = 10_000) {
        runCatching { rule.waitUntil(timeout) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("expected $tag on screen; focused: ${focusedTag()}", it) }
    }

    private fun openSettings() {
        // Back reaches the rail; which entry it lands on depends on where focus was, so walk to Settings from wherever.
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000) { focusedTag()?.startsWith("rail-") == true }
        repeat(Section.entries.size * 2) { if (focusedTag() != ShellTags.rail(Section.SETTINGS)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(ShellTags.rail(Section.SETTINGS))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /**
     * Types into the prompt and saves it the way a viewer does: keyboard away, then the remote. The on-screen keyboard
     * keeps its editor connection for a moment after it hides and swallows the first OK, so the press is repeated until
     * [done] — which is safe here, because saving the same name twice is the same name.
     */
    private fun renameTo(name: String, done: () -> Boolean) {
        awaitExists(LuzPromptTags.PROMPT)
        rule.onNodeWithTag(LuzPromptTags.FIELD).performTextClearance()
        rule.onNodeWithTag(LuzPromptTags.FIELD).performTextInput(name)
        press(KeyEvent.KEYCODE_BACK) // the keyboard takes the first Back; the prompt stays
        awaitExists(LuzPromptTags.PROMPT)
        // Save and "use the provider's name" sit side by side, so Down can land on either.
        repeat(4) {
            if (focusedTag() != LuzPromptTags.CONFIRM) {
                press(if (focusedTag() == LuzPromptTags.CLEAR) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        awaitFocus(LuzPromptTags.CONFIRM)
        rule.pressOkUntil(timeoutMs = 10_000) { done() }
    }

    @Test
    fun hidingAChannelRemovesItFromTheListAndSettingsBringsItBack() {
        // Live TV opens with the category column focused; the channels are one step to the right.
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        val channel = runBlocking { graph.channels(playlist, null) }.first()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LiveTags.channel(channel.id))

        longPressOk()
        awaitExists(LuzMenuTags.MENU)
        repeat(4) { if (focusedTag() != LuzMenuTags.item("hide")) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(LuzMenuTags.item("hide"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        rule.waitUntil(10_000) { runBlocking { graph.channels(playlist, null) }.none { it.id == channel.id } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(LiveTags.channel(channel.id))).fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(channel.id.value), runBlocking { graph.hidden(playlist, CustomisationTarget.CHANNEL) })

        // Settings offers it back, and only offers the section at all because something is hidden.
        openSettings()
        awaitFocus(SettingsTags.entry(SettingsTags.PROVIDERS))
        repeat(4) { if (focusedTag() != SettingsTags.entry(SettingsTags.HIDDEN)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(SettingsTags.entry(SettingsTags.HIDDEN))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        repeat(4) { if (focusedTag() != SettingsTags.hiddenItem(channel.id.value)) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(SettingsTags.hiddenItem(channel.id.value))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        rule.waitUntil(10_000) { runBlocking { graph.channels(playlist, null) }.any { it.id == channel.id } }
        assertTrue(runBlocking { graph.hidden(playlist, CustomisationTarget.CHANNEL) }.isEmpty())
    }

    @Test
    fun aChannelCanBePutIntoAGroupTheViewerMakes() {
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        val channel = runBlocking { graph.channels(playlist, null) }.first()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LiveTags.channel(channel.id))

        longPressOk()
        awaitExists(LuzMenuTags.MENU)
        repeat(5) { if (focusedTag() != LuzMenuTags.item("add-to-group")) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(LuzMenuTags.item("add-to-group"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        // With no groups yet, the only offer is to make one.
        awaitFocus(LuzMenuTags.item("new-group"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        renameTo("My sports") { runBlocking { graph.userGroups(playlist) }.any { it.title == "My sports" } }

        val group = runBlocking { graph.userGroups(playlist) }.single()
        assertEquals(1L, group.channelCount)
        assertEquals(listOf(channel.id), runBlocking { graph.channelsInUserGroup(playlist, group.id) }.map { it.id })

        // It is a category of its own in Live TV, above the provider's.
        awaitExists(LiveTags.group(group.id))
    }

    @Test
    fun renamingACategoryFromItsMenuShowsTheViewersName() {
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        val group = runBlocking { graph.groups(playlist) }.first()

        // Whichever column Live TV opened in, start from the categories.
        if (focusedTag()?.startsWith("live-channel") == true) press(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.waitUntil(5_000) { focusedTag()?.startsWith("live-channel") != true }

        // Down from "All channels", past Favorites, to the provider's first category.
        repeat(6) { if (focusedTag() != LiveTags.group(group.id)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(LiveTags.group(group.id))

        longPressOk()
        awaitExists(LuzMenuTags.MENU)
        awaitFocus(LuzMenuTags.item("rename"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        renameTo("My news") { runBlocking { graph.groups(playlist) }.first { it.id == group.id }.title == "My news" }

        // Clearing a name is covered by the storage test (CustomisationTest): what this one proves is that the remote
        // reaches the rename at all, and that the provider's own title is still there underneath it.
        assertEquals("My news", runBlocking { graph.groupNames(playlist, listOf(group.id)) }[group.id])
    }
}
