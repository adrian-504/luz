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
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.COMPANION_NOTE_TAG
import app.iptvplayer.tv.ui.library.ListMakerTags
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.player.PlayerTags
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

        // Down brings the panel with what is on; Back puts it away.
        rule.waitUntil(10_000) { focusedTag() == PlayerTags.ROOT }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitExists(PlayerTags.TRACK_PANEL)
        awaitFocus(PlayerTags.tab("INFO"))
        shot("live-info-panel")
        press(KeyEvent.KEYCODE_BACK)
        awaitGone(PlayerTags.TRACK_PANEL)
    }
}
