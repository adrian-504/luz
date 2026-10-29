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
import app.iptvplayer.testing.TestPanel
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.guide.GuideTags
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import app.iptvplayer.tv.ui.theme.LuzMenuTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        rule.waitForIdle()
        screenshot("guide")

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
        rule.waitForIdle()
        screenshot("guide-later")
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

    /** Holding OK: the remote sends repeats, and the first of them is the long press (ADR-0031). */
    private fun longPressOk() {
        val down = android.os.SystemClock.uptimeMillis()
        instrumentation.sendKeySync(KeyEvent(down, down, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        Thread.sleep(700)
        instrumentation.sendKeySync(
            KeyEvent(down, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1),
        )
        Thread.sleep(100)
        instrumentation.sendKeySync(
            KeyEvent(down, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0),
        )
        rule.waitForIdle()
        // A person lets go and looks before choosing; a menu ignores OK until the button has been quiet (LuzMenu).
        Thread.sleep(MENU_SETTLE_MS)
    }

    @Test
    fun aChannelsMenuOpensTheGuideOnThatChannel() {
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        val rows = channels()
        val target = rows[4]
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        repeat(6) { if (focusedTag() != LiveTags.channel(target.id)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(LiveTags.channel(target.id))

        longPressOk()
        repeat(6) { if (focusedTag() != LuzMenuTags.item("guide")) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(LuzMenuTags.item("guide"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        // The guide opens with the remote on that channel's row, on what is on now.
        rule.waitUntil(20_000) { focusedTag()?.startsWith("guide-${target.id.value}-") == true }
        awaitDetails(target.name)
    }

    private val playlist get() = runBlocking { graph.sources().single().playlistId }

    /** Opens the guide from Live TV, as the viewer does: Back to the bar, along to Guide, OK. */
    private fun openGuide() {
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.LIVE_TV))
        walkTabsTo(Section.GUIDE, ::focusedTag, { key -> press(key) }) { timeout, condition ->
            runCatching { rule.waitUntil(timeout, condition) }
        }
        awaitFocus(ShellTags.rail(Section.GUIDE))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun groupWith(channelName: String) = runBlocking { graph.groups(playlist) }.first { group ->
        runBlocking { graph.channels(playlist, group.id) }.any { it.name == channelName }
    }

    /** The guide opens on the category last used in Live TV (x10), named in its chip, and shows only its channels. */
    @Test
    fun theGuideOpensOnTheLastGroup() {
        val sports = groupWith("Test Sports")
        runBlocking { graph.setPreference(graph.liveScopeKey(playlist), ChannelScope.Group(sports.id).key()) }
        openGuide()
        rule.waitUntil(20_000) {
            val chip = hasTestTag(GuideTags.SCOPE).and(hasText(sports.title, substring = true, ignoreCase = true))
            rule.onAllNodes(chip, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val news = runBlocking { graph.channels(playlist, null) }.first { it.name == "Test News HD" }
        assertTrue(
            "a channel from another category is not in the guide",
            rule.onAllNodes(hasText(news.label), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** A channel without listings is one quiet bar with its name (x10), never "No information" boxes. */
    @Test
    fun aChannelWithoutListingsIsOneQuietBar() {
        TestPanel.withoutGuide = setOf(401, 402, 403)
        try {
            runBlocking { graph.setPreference(graph.liveScopeKey(playlist), ChannelScope.Group(groupWith("Test Copies HD").id).key()) }
            openGuide()
            rule.waitUntil(30_000) {
                rule.onAllNodes(hasText("Test Copies · no listings"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            TestPanel.withoutGuide = emptySet()
        }
    }

    /** OK on a programme on now plays the channel, and Ch + stays inside the guide's category (x4). */
    @Test
    fun zappingFromTheGuideStaysInItsGroup() {
        val sports = groupWith("Test Sports")
        val inSports = runBlocking { graph.channels(playlist, sports.id) }
        runBlocking { graph.setPreference(graph.liveScopeKey(playlist), ChannelScope.Group(sports.id).key()) }
        openGuide()
        awaitFocus(GuideTags.FIRST_CELL)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(20_000) { CompanionHub.playing.value?.title == inSports[0].label }
        repeat(inSports.size) {
            press(KeyEvent.KEYCODE_CHANNEL_UP)
            Thread.sleep(800)
            assertTrue("zapping stayed in Sports", inSports.any { it.label == CompanionHub.playing.value?.title })
        }
    }

    /** Fix the guide (x9): choosing another guide channel links it at once, and taking the fix away unlinks it. */
    @Test
    fun fixingTheGuideLinksTheChosenGuideChannel() {
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        val channel = runBlocking { graph.channels(playlist, null) }.first { it.name == "Test News HD" }
        val choices = runBlocking { graph.guideChoices(playlist, channel.id) }
        val other = choices.channels.first { it.key != choices.current?.first }
        runBlocking { graph.fixGuide(playlist, channel.id, other) }
        assertEquals(other.key to true, runBlocking { graph.guideChoices(playlist, channel.id) }.current)
        runBlocking { graph.resetGuide(playlist, channel.id) }
        rule.waitUntil(30_000) { runBlocking { graph.guideChoices(playlist, channel.id) }.current?.second != true }
    }
}

private const val MENU_SETTLE_MS = 300L
