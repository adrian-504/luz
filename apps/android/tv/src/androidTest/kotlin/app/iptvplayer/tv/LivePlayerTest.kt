package app.iptvplayer.tv

import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.live.NewsChannels
import app.iptvplayer.domain.live.ViewingSession
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.testing.TestPanel
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.COMPANION_NOTE_TAG
import app.iptvplayer.tv.ui.library.ListMakerTags
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.LivePreviewTags
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.player.LAST_CHANNEL_SCOPE
import app.iptvplayer.tv.ui.player.LIVE_BAR_SECONDS
import app.iptvplayer.tv.ui.player.PlayerTags
import app.iptvplayer.tv.ui.player.StripTags
import app.iptvplayer.tv.ui.theme.LuzPromptTags
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

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
        rule.waitForIdle()
        screenshot(name)
    }

    /** The phone companion's "now playing" (ADR-0050): the phone sees the channel and changes it. */
    @Test
    fun aPairedPhoneSeesTheChannelAndChangesIt() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
            val channels = runBlocking { graph.channels(playlist, null) }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus(LiveTags.channel(channels.first().id))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus(PlayerTags.PLAY_PAUSE, timeout = 20_000)
            val token = JSONObject(
                companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""").second,
            ).getString("token")

            rule.waitUntil(5_000) { CompanionHub.playing.value?.title == channels.first().label }
            assertTrue(CompanionHub.playing.value!!.live)
            assertEquals(200, companionCall("/api/key", """{"token":"$token","key":"channel-up"}""").first)
            rule.waitUntil(10_000) { CompanionHub.playing.value?.title == channels[1].label }
        } finally {
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
    }

    /** Browsing on the phone (ADR-0050, step 3): the phone finds a channel, sees what is on, and plays it on the TV. */
    @Test
    fun aPairedPhoneFindsAChannelAndPlaysItOnTheTv() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
            val target = runBlocking { graph.channels(playlist, null) }[2]
            val token = JSONObject(
                companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""").second,
            ).getString("token")
            assertEquals(401, companionGet("/api/home", "wrong").first)

            val home = JSONObject(companionGet("/api/home", token).second)
            assertTrue(home.getBoolean("source"))
            assertTrue(home.getJSONArray("groups").length() > 0)
            val found = JSONObject(
                companionGet(Uri.Builder().path("/api/search").appendQueryParameter("q", target.label).build().toString(), token).second,
            ).getJSONArray("channels")
            val hit = (0 until found.length()).map(found::getJSONObject).first { it.getString("id") == target.id.value }
            assertEquals(target.label, hit.getString("name"))

            val (status, _) = companionCall("/api/play", """{"token":"$token","kind":"channel","id":"${target.id.value}","scope":"all"}""")
            assertEquals(200, status)
            awaitExists(PlayerTags.TITLE, target.label, timeout = 20_000)
            // Something that is not there is refused, and nothing opens.
            assertEquals(404, companionCall("/api/play", """{"token":"$token","kind":"movie","id":"no-such-film"}""").first)
        } finally {
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
    }

    /**
     * Lists from the phone (ADR-0050, 2b; Design 2.8–2.13): make one, fill it from search, see it on the TV, rename it, be
     * refused a name already used, favourite a channel, and delete the list; the TV shows a note for the changes.
     */
    @Test
    fun aPhoneMakesAListFillsItAndTheTvSaysSo() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
            val (first, second) = runBlocking { graph.channels(playlist, null) }.let { it[0] to it[1] }
            val token = JSONObject(
                companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""").second,
            ).getString("token")

            val made = companionCall("/api/list", """{"token":"$token","action":"create","title":"My sports"}""")
            assertEquals(200, made.first)
            val id = JSONObject(made.second).getString("id")
            awaitExists(COMPANION_NOTE_TAG)
            assertEquals(409, companionCall("/api/list", """{"token":"$token","action":"create","title":"my sports"}""").first)
            // No name: the TV picks one.
            val unnamed = JSONObject(companionCall("/api/list", """{"token":"$token","action":"create"}""").second)
            assertEquals("New list", unnamed.getString("title"))

            fun add(channel: String) = companionCall(
                "/api/list",
                """{"token":"$token","action":"add","id":"$id","kind":"channel","item":"$channel"}""",
            )
            assertEquals(200, add(first.id.value).first)
            assertEquals(2, JSONObject(add(second.id.value).second).getInt("count"))
            assertTrue(runBlocking { graph.channelsInUserGroup(playlist, id) }.map { it.id } == listOf(first.id, second.id))

            val search = Uri.Builder().path("/api/search").appendQueryParameter("q", first.label).appendQueryParameter("list", id).build()
            val hits = JSONObject(companionGet(search.toString(), token).second).getJSONArray("channels")
            assertTrue((0 until hits.length()).map(hits::getJSONObject).first { it.getString("id") == first.id.value }.getBoolean("added"))

            val home = JSONObject(companionGet("/api/home", token).second).getJSONArray("lists")
            assertTrue((0 until home.length()).map(home::getJSONObject).any { it.getString("id") == id && it.getInt("channels") == 2 })
            val item = JSONObject(
                companionGet(
                    Uri.Builder().path(
                        "/api/item",
                    ).appendQueryParameter("kind", "channel").appendQueryParameter("id", first.id.value).build().toString(),
                    token,
                ).second,
            )
            val lists = item.getJSONArray("lists")
            assertTrue((0 until lists.length()).map(lists::getJSONObject).single { it.getString("id") == id }.getBoolean("in"))

            assertEquals(200, companionCall("/api/list", """{"token":"$token","action":"rename","id":"$id","title":"Football"}""").first)
            assertEquals(
                409,
                companionCall("/api/list", """{"token":"$token","action":"rename","id":"$id","title":"New list"}""").first,
            )
            assertEquals(
                200,
                companionCall("/api/favorite", """{"token":"$token","kind":"channel","id":"${first.id.value}","on":true}""").first,
            )
            rule.waitUntil(5_000) { runBlocking { graph.favoriteChannels(playlist) }.any { it.id == first.id } }

            assertEquals(200, companionCall("/api/list", """{"token":"$token","action":"delete","id":"$id"}""").first)
            assertEquals(404, companionGet("/api/list?id=$id", token).first)
        } finally {
            runBlocking { graph.userGroups(playlist).forEach { graph.deleteGroup(playlist, it.id) } }
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
    }

    /**
     * Making a list on the TV (Design 2.9): New list…, "Name it later", search, OK adds and OK again takes out, and Back
     * asks for a name once ("Later" keeps "New list"). The phone's notes stay quiet for the list being filled.
     */
    @Test
    fun aListIsMadeOnTheTvBySearchingAndPressingOk() {
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        val target = runBlocking { graph.channels(playlist, null) }[1]
        try {
            repeat(4) { if (focusedTag() != LiveTags.NEW_LIST) press(KeyEvent.KEYCODE_DPAD_DOWN) }
            awaitFocus(LiveTags.NEW_LIST)
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitExists(LuzPromptTags.CLEAR)
            rule.onNodeWithTag(LuzPromptTags.CLEAR).performClick()
            awaitExists(ListMakerTags.HEADER)
            val list = runBlocking { graph.userGroups(playlist) }.single()
            assertEquals("New list", list.title)
            assertEquals(list.id, CompanionHub.addingTo.value)

            rule.onNodeWithTag(SearchTags.FIELD).performTextInput(target.label)
            val row = ListMakerTags.row("channel", target.id.value)
            awaitExists(row)
            rule.onNodeWithTag(row).performClick()
            rule.waitUntil(5_000) { runBlocking { graph.channelsInUserGroup(playlist, list.id) }.map { it.id } == listOf(target.id) }
            awaitExists(ListMakerTags.HEADER, "New list · 1")
            rule.onNodeWithTag(row).performClick()
            rule.waitUntil(5_000) { runBlocking { graph.channelsInUserGroup(playlist, list.id) }.isEmpty() }
            rule.onNodeWithTag(row).performClick()
            rule.waitUntil(5_000) { runBlocking { graph.channelsInUserGroup(playlist, list.id) }.size == 1 }

            press(KeyEvent.KEYCODE_BACK)
            awaitExists(LuzPromptTags.CLEAR)
            rule.onNodeWithTag(LuzPromptTags.CLEAR).performClick()
            awaitGone(ListMakerTags.SCREEN)
            assertEquals(null, CompanionHub.addingTo.value)
            awaitFocus(LiveTags.group(list.id))
        } finally {
            runBlocking { graph.userGroups(playlist).forEach { graph.deleteGroup(playlist, it.id) } }
        }
    }

    /** Puts the remote on the channel at [index] of "All channels" and returns its tag. */
    private fun focusChannel(index: Int): String {
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val tag = LiveTags.channel(runBlocking { graph.channels(playlist, null) }[index].id)
        repeat(index + 1) { if (focusedTag() != tag) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(tag)
        return tag
    }

    private fun refreshChannels() {
        graph.refresh(playlist)
        rule.waitUntil(5_000) { graph.activity.value[playlist]?.liveRunning == true }
        rule.waitUntil(30_000) { graph.activity.value[playlist]?.liveRunning != true }
        rule.waitForIdle()
    }

    /** A refresh replaces the rows in place (ADR-0051, Design 1.2): the remote stays on the channel it was on. */
    @Test
    fun refreshingTheChannelsKeepsTheRemoteOnTheSameChannel() {
        val tag = focusChannel(3)
        refreshChannels()
        Thread.sleep(500)
        assertEquals(tag, focusedTag())
    }

    /** If the channel under the remote vanishes in a refresh, the remote takes the one now in its place. */
    @Test
    fun aChannelThatDisappearsHandsTheRemoteToTheOneInItsPlace() {
        val channels = runBlocking { graph.channels(playlist, null) }
        val index = channels.indexOfFirst { it.name == "Test Sports Extra" }
        focusChannel(index)
        TestPanel.dropped = setOf(202)
        try {
            refreshChannels()
            val after = runBlocking { graph.channels(playlist, null) }
            assertTrue(after.none { it.name == "Test Sports Extra" })
            awaitFocus(LiveTags.channel(after[index.coerceAtMost(after.lastIndex)].id))
        } finally {
            TestPanel.dropped = emptySet()
        }
    }

    /** Luz opens on the category used last with this source (ADR-0051), and remembers it apart for each source. */
    @Test
    fun luzOpensOnTheLastCategoryWithItsChannels() {
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        val sports = runBlocking { graph.groups(playlist) }.first { group ->
            runBlocking { graph.channels(playlist, group.id) }.any { it.name == "Test Sports" }
        }
        val tag = LiveTags.group(sports.id)
        repeat(8) { if (focusedTag() != tag) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(tag)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        val first = runBlocking { graph.channels(playlist, sports.id) }.first()
        awaitFocus(LiveTags.channel(first.id))
        rule.waitUntil(5_000) { runBlocking { graph.preference(graph.liveScopeKey(playlist)) } == "g:${sports.id}" }

        // A recreated screen shows the category and its channels, not "All channels". (Recreating keeps the screen's own
        // saved state; a real cold start, read from the saved setting above, is checked on the TV: TESTING.md §4.5.)
        rule.activityRule.scenario.recreate()
        awaitExists(LiveTags.channel(first.id))
        val all = runBlocking { graph.channels(playlist, null) }
        assertTrue(
            all.filter { c -> c.id != first.id && runBlocking { graph.channels(playlist, sports.id) }.none { it.id == c.id } }
                .none { rule.onAllNodes(hasTestTag(LiveTags.channel(it.id))).fetchSemanticsNodes().isNotEmpty() },
        )
    }

    private fun playChannel(index: Int) {
        focusChannel(index)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(PlayerTags.PLAY_PAUSE, timeout = 20_000)
    }

    /**
     * One channel from three copies (ADR-0052): "Test Copies" is one row, its FHD copy is offline, so Luz quietly plays
     * the HD one, and remembers: next time HD comes first and FHD last.
     */
    @Test
    fun aFailingCopyFallsBackToTheNextOne() {
        val all = runBlocking { graph.channels(playlist, null) }
        val copies = all.filter { it.label == "Test Copies" }
        assertEquals("three copies, one row", 1, copies.size)
        assertEquals("the best copy is the one shown", "FHD", copies.single().quality)
        playChannel(all.indexOf(copies.single()))
        rule.waitUntil(30_000) {
            runBlocking { graph.channelCopies(playlist, copies.single().id) }.first().value == "402"
        }
        assertEquals("401", runBlocking { graph.channelCopies(playlist, copies.single().id) }.last().value)
        assertTrue(
            "no error while another copy plays",
            rule.onAllNodes(hasTestTag(PlayerTags.ERROR_MESSAGE)).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** The viewing log (round 3): one session for a channel watched past the (lowered) threshold, none before it. */
    @Test
    fun watchingLogsOneSession() {
        graph.sessionMinSeconds = 2
        try {
            playChannel(0)
            Thread.sleep(4_000)
            press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10_000) { runBlocking { graph.sessionCount(playlist) } == 1L }
        } finally {
            graph.sessionMinSeconds = ViewingSession.MIN_SECONDS
        }
    }

    /** "Channel bar stays" (x7): the live bar after a channel change stays for the chosen time. */
    @Test
    fun theChannelBarStaysForTheChosenTime() {
        runBlocking { graph.setPreference(LIVE_BAR_SECONDS, "8") }
        try {
            playChannel(0)
            // The controls give way to the picture; a channel change then shows the bar alone.
            press(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(PlayerTags.PLAY_PAUSE)).fetchSemanticsNodes().isEmpty() }
            press(KeyEvent.KEYCODE_CHANNEL_UP)
            awaitExists(PlayerTags.BANNER)
            Thread.sleep(5_500)
            assertTrue("still there after 5 s", rule.onAllNodes(hasTestTag(PlayerTags.BANNER)).fetchSemanticsNodes().isNotEmpty())
            awaitGone(PlayerTags.BANNER)
        } finally {
            runBlocking { graph.setPreference(LIVE_BAR_SECONDS, null) }
        }
    }

    /**
     * Opening on the last channel (3.9): the channel played is the one Luz finds to open on, in the list it came from.
     * The cold start itself (a new process) is checked on the TV: TESTING.md §4.5.
     */
    @Test
    fun theLastChannelIsRememberedForTheNextStart() {
        val channels = runBlocking { graph.channels(playlist, null) }
        playChannel(2)
        rule.waitUntil(10_000) { runBlocking { graph.lastChannel(playlist) } == channels[2].id }
        assertEquals("all", runBlocking { graph.preference(LAST_CHANNEL_SCOPE) })
    }

    /** A category the provider adds after setup waits in Settings, marked new, instead of appearing in Live TV (x6). */
    @Test
    fun aGroupAddedLaterWaitsInSettings() {
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        assertTrue("the first import shows every category", runBlocking { graph.newGroups(playlist) }.isEmpty())
        TestPanel.lateCategory = true
        try {
            refreshChannels()
            assertEquals(1, runBlocking { graph.newGroups(playlist) }.size)
            assertTrue(runBlocking { graph.groups(playlist) }.none { it.title == TestPanel.LATE_CATEGORY })
            assertTrue(runBlocking { graph.groupsToArrange(playlist) }.any { it.title == TestPanel.LATE_CATEGORY && it.hidden })
        } finally {
            TestPanel.lateCategory = false
        }
    }

    /** Nothing opens out of sight (ADR-0050, step 3): while Luz is not on the TV's screen, the phone is told so. */
    @Test
    fun aPhoneCannotOpenAnythingWhileLuzIsHidden() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
            val target = runBlocking { graph.channels(playlist, null) }.first()
            val token = JSONObject(
                companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""").second,
            ).getString("token")
            // Stopped, as when the viewer presses Home and the TV shows another app.
            rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            rule.waitUntil(5_000) { !CompanionHub.onScreen.value }
            val (status, body) = companionCall(
                "/api/play",
                """{"token":"$token","kind":"channel","id":"${target.id.value}","scope":"all"}""",
            )
            assertEquals(409, status)
            assertTrue(body.contains("luz-hidden"))

            rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            // Back on screen, Live TV is where it was: nothing was opened while it was away.
            awaitFocus(LiveTags.GROUP_ALL, timeout = 10_000)
        } finally {
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
    }

    /** x2: the phone hears the TV's name first, then what is playing with a picture's reference, never an address. */
    @Test
    fun thePhoneHearsTheTvNameAndWhatIsPlaying() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
            val channel = runBlocking { graph.channels(playlist, null) }.first()
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus(LiveTags.channel(channel.id))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus(PlayerTags.PLAY_PAUSE, timeout = 20_000)
            val token = JSONObject(
                companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""").second,
            ).getString("token")

            val events = companionEvents(token, millis = 3_000)
            assertTrue(events, events.indexOf("event: tv") in 0 until events.indexOf("event: playing"))
            val playing = events.substringAfter("event: playing\ndata: ").substringBefore('\n')
            assertTrue(playing, playing.contains(channel.label))
            if (channel.logo != null) assertTrue(playing, playing.contains("\"art\":\"c:${channel.id.value}\""))
            assertFalse(playing, playing.contains("http"))
        } finally {
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
    }

    /** x2: a phone's Ch + changes the channel and the TV says so at the top for two seconds. */
    @Test
    fun aPhoneChangeShowsANoteOnTheTv() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
            val channels = runBlocking { graph.channels(playlist, null) }
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            awaitFocus(LiveTags.channel(channels.first().id))
            press(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitFocus(PlayerTags.PLAY_PAUSE, timeout = 20_000)
            val token = JSONObject(
                companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""").second,
            ).getString("token")
            assertEquals(200, companionCall("/api/key", """{"token":"$token","key":"channel-up"}""").first)
            awaitExists(COMPANION_NOTE_TAG, "from your phone", timeout = 3_000)
            // The arrow pad shows nothing: it would on every press.
            rule.waitUntil(4_000) { rule.onAllNodes(hasTestTag(COMPANION_NOTE_TAG)).fetchSemanticsNodes().isEmpty() }
            assertEquals(200, companionCall("/api/key", """{"token":"$token","key":"down"}""").first)
            Thread.sleep(500)
            assertTrue(rule.onAllNodes(hasTestTag(COMPANION_NOTE_TAG)).fetchSemanticsNodes().isEmpty())
        } finally {
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
    }

    @Test
    fun aChannelNumberTypedOnTheRemoteTunesToThatChannel() {
        awaitFocus(LiveTags.GROUP_ALL, timeout = 20_000)
        val channels = runBlocking { graph.channels(playlist, null) }
        val first = channels.first()
        val target = channels.drop(1).first { it.number != null }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LiveTags.channel(first.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(PlayerTags.PLAY_PAUSE, timeout = 20_000)
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(PlayerTags.ROOT)

        // The digits tune once the viewer stops typing (the box that shows them is gone by the time the test looks).
        target.number.toString().forEach { digit -> press(KeyEvent.KEYCODE_0 + (digit - '0')) }
        awaitGone(PlayerTags.TYPED_NUMBER)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitExists(PlayerTags.TITLE, target.label)
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
        awaitExists(PlayerTags.TITLE, first.label)
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

        // The owner's decision (ADR-0053, Design D4/A): in live TV Down raises the channel strip, and the panel with what
        // is on comes from the Info button in the controls. Back puts each away.
        rule.waitUntil(10_000) { focusedTag() == PlayerTags.ROOT }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(StripTags.STRIP)
        shot("live-strip")
        press(KeyEvent.KEYCODE_BACK)
        awaitGone(StripTags.STRIP)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(PlayerTags.PLAY_PAUSE)
        repeat(8) { if (focusedTag() != PlayerTags.INFO) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(PlayerTags.INFO)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitExists(PlayerTags.TRACK_PANEL)
        awaitFocus(PlayerTags.tab("INFO"))
        shot("live-info-panel")
        press(KeyEvent.KEYCODE_BACK)
        awaitGone(PlayerTags.TRACK_PANEL)
    }

    /** Puts the picture alone on screen while a channel plays, as when the viewer has put the controls away. */
    private fun pictureAlone() {
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { focusedTag() == PlayerTags.ROOT }
    }

    /** Down while watching (ADR-0053): the strip opens on Recent with the playing channel under the remote. */
    @Test
    fun downRaisesTheStripOnRecentWithThePlayingChannel() {
        val channels = runBlocking { graph.channels(playlist, null) }
        playChannel(0)
        pictureAlone()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(StripTags.STRIP)
        awaitFocus(StripTags.card(ChannelScope.Recent.key(), channels[0].id.value))
    }

    /** Up from the cards reaches the tabs; Right moves to Favourites and its cards (or its empty words) show at once. */
    @Test
    fun upFromTheStripReachesTheTabsAndChangesTab() {
        playChannel(0)
        pictureAlone()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(StripTags.STRIP)
        press(KeyEvent.KEYCODE_DPAD_UP)
        awaitFocus(StripTags.tab(ChannelScope.Recent.key()))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(StripTags.tab(ChannelScope.Favorites.key()))
        awaitExists(StripTags.STRIP, rule.activity.getString(R.string.strip_empty_fav_title))
    }

    /** OK on a card tunes; the strip holds a moment on the new channel, then lowers. */
    @Test
    fun okOnAStripCardTunesAndTheStripCloses() {
        val channels = runBlocking { graph.channels(playlist, null) }
        playChannel(1)
        pictureAlone()
        // Recent now holds two channels: the second (playing) and, after a zap back, the first.
        press(KeyEvent.KEYCODE_CHANNEL_DOWN)
        awaitExists(PlayerTags.TITLE, channels[0].label)
        rule.waitUntil(10_000) { runBlocking { graph.recentChannels(playlist, 5) }.size == 2 }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(StripTags.STRIP)
        // Recent is newest first: the playing channel, then the one before it.
        awaitFocus(StripTags.card(ChannelScope.Recent.key(), channels[0].id.value))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(StripTags.card(ChannelScope.Recent.key(), channels[1].id.value))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitExists(PlayerTags.TITLE, channels[1].label)
        awaitGone(StripTags.STRIP)
    }

    /** Eight seconds without a press lowers the strip. */
    @Test
    fun theStripClosesAfterEightSeconds() {
        playChannel(0)
        pictureAlone()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(StripTags.STRIP)
        Thread.sleep(6_000)
        assertTrue("still up after 6 s", rule.onAllNodes(hasTestTag(StripTags.STRIP)).fetchSemanticsNodes().isNotEmpty())
        awaitGone(StripTags.STRIP)
    }

    /** The News tab (s0): the test panel's Arabic and French news channels, in that order, labelled by language. */
    @Test
    fun theNewsTabListsNewsByLanguage() {
        val news = runBlocking { graph.newsChannels(playlist) }
        assertEquals(listOf("Test News Arabic"), news[NewsChannels.Language.ARABIC]?.map { it.label })
        assertEquals(listOf("Test Info"), news[NewsChannels.Language.FRENCH]?.map { it.label })
        playChannel(0)
        pictureAlone()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(StripTags.tab(ChannelScope.News.key()))
    }

    /** "Choose copy" (x9): choosing the SD copy plays it and keeps it first until another is chosen. */
    @Test
    fun chooseCopyPlaysThatCopyAndRemembersIt() {
        val copies = runBlocking { graph.channels(playlist, null) }.single { it.label == "Test Copies" }
        val sd = runBlocking { graph.copyChoices(playlist, copies.id) }.single { it.quality == "SD" }
        runBlocking { graph.chooseCopy(playlist, sd.id) }
        assertEquals(sd.id, runBlocking { graph.channelCopies(playlist, copies.id) }.first())
        assertEquals("SD", runBlocking { graph.channels(playlist, null) }.single { it.label == "Test Copies" }.quality)
        assertTrue(runBlocking { graph.copyChoices(playlist, copies.id) }.single { it.id == sd.id }.chosen)
    }

    /** The live preview (D2): after the remote rests on a channel, the box shows its picture; leaving Live TV stops it. */
    @Test
    fun thePreviewPlaysAfterARestAndLeavingLiveTvStopsIt() {
        focusChannel(0)
        rule.waitUntil(15_000) { graph.playing }
        assertTrue(rule.onAllNodes(hasTestTag(LivePreviewTags.BOX)).fetchSemanticsNodes().isNotEmpty())
        // To the categories: the remote has left the channel list, so the preview stops.
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        rule.waitUntil(5_000) { !graph.playing }
    }
}
