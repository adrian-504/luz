package app.iptvplayer.tv

import android.os.SystemClock
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
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.storage.FollowKind
import app.iptvplayer.testing.TestPanel
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.library.HomeTags
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.player.PlayerTags
import app.iptvplayer.tv.ui.player.StripTags
import app.iptvplayer.tv.ui.settings.SettingsTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import app.iptvplayer.tv.ui.sport.KickOffTags
import app.iptvplayer.tv.ui.sport.LeagueTableTags
import app.iptvplayer.tv.ui.sport.MatchGuideTags
import app.iptvplayer.tv.ui.theme.LuzPromptTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Sport with the viewer's own football-data.org key (ADR-0057), against the test server's fakes of football-data.org and
 * Wikidata: the key entered in Settings → Sport, a team found in Search with its next match, and following it by holding
 * OK on the match. Requests are spaced 7.5 s apart, so these tests take their time.
 */
@RunWith(AndroidJUnit4::class)
class SportFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    private val provider = object : ExternalResource() {
        override fun before() {
            DeveloperStreams.useTestSport = true
            forgetSport()
            val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
            runBlocking { assertTrue(graph.addXtream("Sport test", server, username, password) is AddSourceResult.Added) }
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(provider).around(rule)

    private val playlist get() = runBlocking { graph.sources().single().playlistId }

    @After
    fun forget() {
        graph.reminderLead = 2.minutes
        forgetSport()
        DeveloperStreams.useTestSport = false
    }

    /** What is followed is not per source, so it outlives the source: cleared by hand around each test. */
    private fun forgetSport() = runBlocking {
        graph.follows().forEach { graph.unfollow(it.kind, it.ref) }
        graph.removeSportToken()
    }

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

    private fun openSection(section: Section, waitForLive: Boolean = true) {
        if (waitForLive) {
            rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
            press(KeyEvent.KEYCODE_BACK)
        }
        rule.waitUntil(5_000) { focusedTag()?.startsWith("rail-") == true }
        walkTabsTo(
            section,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(ShellTags.rail(section))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    /** OK held as the remote holds it: down, a long-press repeat, then up. */
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

    private fun shot(name: String) {
        rule.waitForIdle()
        screenshot(name)
    }

    /** The catalogue (the competitions the key can read and their teams) is read after the key is kept. */
    private fun awaitCatalogue() = rule.waitUntil(60_000) {
        runBlocking { graph.followable("example city").first.isNotEmpty() }
    }

    @Test
    fun aKeyEnteredInSettingsIsKeptAndWhatIsFollowedIsListed() {
        openSection(Section.SETTINGS)
        awaitFocus(SettingsTags.entry(SettingsTags.PROVIDERS))
        repeat(8) { if (focusedTag() != SettingsTags.entry(SettingsTags.SPORT)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(SettingsTags.entry(SettingsTags.SPORT))
        press(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT)
        // Right lands on the pane's nearest button; the key's is above "Find a team".
        repeat(3) { if (focusedTag() != SettingsTags.SPORT_KEY) press(KeyEvent.KEYCODE_DPAD_UP) }
        awaitFocus(SettingsTags.SPORT_KEY)
        shot("r4-settings-sport-no-key")
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(LuzPromptTags.PROMPT)).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-settings-sport-key-box")
        rule.onNodeWithTag(LuzPromptTags.FIELD).performTextInput(TestPanel.FOOTBALL_TOKEN)
        // The keyboard waits for OK on the field, so nothing stands between the field and Save.
        repeat(4) { if (focusedTag() != LuzPromptTags.CONFIRM) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(LuzPromptTags.CONFIRM)
        rule.pressOkUntil(timeoutMs = 10_000) { rule.onAllNodes(hasTestTag(LuzPromptTags.PROMPT)).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(30_000) {
            rule.onAllNodes(
                hasTestTag(SettingsTags.SPORT).and(hasText("Key accepted", substring = true)),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { graph.sportStatus().hasToken })
        shot("r4-settings-sport-empty")

        graph.follow(FollowKind.TEAM, "9065", "Example City FC")
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(SettingsTags.followed("9065"))).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-settings-sport")
        // OK on a followed name unfollows it, asking nothing.
        rule.onNodeWithTag(SettingsTags.followed("9065")).performClick()
        rule.waitUntil(10_000) { runBlocking { graph.follows() }.isEmpty() }
    }

    @Test
    fun searchingATeamShowsItsNextMatchAndItsMenuFollowsIt() {
        assertNull(runBlocking { graph.setSportToken(TestPanel.FOOTBALL_TOKEN) })
        awaitCatalogue()
        openSection(Section.SEARCH)
        awaitFocus(SearchTags.FIELD, timeout = 20_000)
        rule.onNodeWithTag(SearchTags.FIELD).performTextInput("example city")
        // The team's matches are read when it is first searched for: one request, after the spacing.
        val card = SearchTags.result("match", "70001")
        rule.waitUntil(40_000) { rule.onAllNodes(hasTestTag(card)).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-search-matches")

        // Holding OK on the card: the match's menu (4.8).
        repeat(8) {
            val at = focusedTag()
            when {
                at == card -> Unit
                // Matches are soonest first: a later one is to the right of the one wanted.
                at?.startsWith("search-match-") == true ->
                    press(if (at.removePrefix("search-match-") > "70001") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                else -> press(KeyEvent.KEYCODE_DPAD_DOWN)
            }
        }
        awaitFocus(card)
        longPressOk()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Follow Example City FC")).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-match-menu")
        rule.onAllNodes(hasText("Follow Example City FC"))[0].performClick()
        rule.waitUntil(10_000) { runBlocking { graph.isFollowed(FollowKind.TEAM, "9065") } }
        assertEquals(listOf("Example City FC"), runBlocking { graph.follows() }.map { it.name })

        // OK: "In your guide" (4.3), the guide's programmes for both teams first, then one team's.
        rule.onNodeWithTag(card).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(MatchGuideTags.SCREEN)).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(30_000) {
            val first = hasTestTag(MatchGuideTags.row(0)).and(hasText("Example City v Sample United", substring = true))
            rule.onAllNodes(first).fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasText("Example City magazine", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("r4-match-guide")
        rule.onNodeWithTag(MatchGuideTags.REMIND).performClick()
        rule.waitUntil(10_000) { runBlocking { graph.hasReminder("70001") } }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Reminder set")).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-match-guide-reminder")

        // A match no programme in the guide names: one quiet sentence, and why.
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(SearchTags.FIELD)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(SearchTags.FIELD).performTextReplacement("wanderers")
        val unlisted = SearchTags.result("match", "70004")
        rule.waitUntil(40_000) { rule.onAllNodes(hasTestTag(unlisted)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(unlisted).performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(MatchGuideTags.EMPTY)).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-match-guide-empty")
    }

    /** A reminded match raises the kick-off card wherever the viewer is (4.4); "Find it in your guide" opens its page. */
    @Test
    fun aReminderRaisesTheKickOffCardAndItsGuide() {
        assertNull(runBlocking { graph.setSportToken(TestPanel.FOOTBALL_TOKEN) })
        graph.follow(FollowKind.TEAM, "9065", "Example City FC")
        rule.waitUntil(60_000) { runBlocking { graph.fixture("70001") } != null }
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        // Tonight's match is due now: the card rises over Live TV.
        graph.reminderLead = 24.hours
        runBlocking { graph.remind("70001", true) }
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(KickOffTags.CARD)).fetchSemanticsNodes().isNotEmpty() }
        awaitFocus(KickOffTags.FIND)
        shot("r4-kickoff-card")
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(MatchGuideTags.SCREEN)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasTestTag(KickOffTags.CARD)).fetchSemanticsNodes().isEmpty())
    }

    /**
     * Match day and Sport now (s1, 4.17): with a followed team's match under way, Home leads with it ("Live now" on the
     * stage), Sport now lists it, and the strip gains a Sport now tab of what the guide names.
     */
    @Test
    fun aFollowedTeamsMatchLeadsHomeAndSportNow() {
        assertNull(runBlocking { graph.setSportToken(TestPanel.FOOTBALL_TOKEN) })
        graph.follow(FollowKind.TEAM, "9065", "Example City FC")
        rule.waitUntil(60_000) { runBlocking { graph.fixture("70000") } != null && graph.sportRevision.value > 0 }
        // The team's names (Arabic too) arrive with the follow; the strip's tab searches the guide by them.
        rule.waitUntil(30_000) { runBlocking { graph.sportGuide(playlist, kotlin.time.Clock.System.now()) }.isNotEmpty() }
        openSection(Section.HOME)
        val first = HomeTags.item(HomeTags.MATCH_DAY, "70000")
        runCatching { rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(first)).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure {
                shot("r4-matchday-missing")
                val day = runBlocking { graph.matchDay(kotlin.time.Clock.System.now()) }
                throw AssertionError("match day: $day; focused ${focusedTag()}", it)
            }
        awaitFocus(first, timeout = 20_000)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Live now"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-matchday-home")
        // Sport now, further down: the day's match among the others.
        val sportNow = HomeTags.item(HomeTags.SPORT_NOW, "70000")
        repeat(6) { if (focusedTag()?.startsWith("home-${HomeTags.SPORT_NOW}") != true) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(sportNow)).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-sport-now-row")

        // On live television, Down: the strip's last tab is Sport now.
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000) { focusedTag()?.startsWith("rail-") == true }
        openSection(Section.LIVE_TV, waitForLive = false)
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        repeat(3) { if (focusedTag()?.startsWith("live-channel-") != true) press(KeyEvent.KEYCODE_DPAD_RIGHT) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(PlayerTags.ROOT)).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(StripTags.tab("sport"))).fetchSemanticsNodes().isNotEmpty() }
        repeat(6) {
            val at = focusedTag()
            if (at != StripTags.tab("sport")) {
                press(if (at?.startsWith("player-strip-tab") == true) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_UP)
            }
        }
        shot("r4-strip-sport")
    }

    /** A followed league's table (4.7), from Settings → Sport: opened on the viewer's team, which is raised. */
    @Test
    fun aFollowedLeaguesTableOpensOnTheViewersTeam() {
        assertNull(runBlocking { graph.setSportToken(TestPanel.FOOTBALL_TOKEN) })
        graph.follow(FollowKind.TEAM, "9066", "Sample United")
        graph.follow(FollowKind.COMPETITION, "EXL", "Example League")
        rule.waitUntil(60_000) { runBlocking { graph.leagueTable("EXL") }.rows.size == 3 }
        openSection(Section.SETTINGS)
        awaitFocus(SettingsTags.entry(SettingsTags.PROVIDERS))
        repeat(8) { if (focusedTag() != SettingsTags.entry(SettingsTags.SPORT)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(SettingsTags.followed("EXL"))).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-settings-sport-league")
        rule.onNodeWithTag(SettingsTags.followed("EXL")).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(LeagueTableTags.SCREEN)).fetchSemanticsNodes().isNotEmpty() }
        awaitFocus(LeagueTableTags.row("9066"))
        shot("r4-league-table")
    }
}
