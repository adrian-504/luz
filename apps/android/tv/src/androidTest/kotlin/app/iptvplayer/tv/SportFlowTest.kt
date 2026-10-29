package app.iptvplayer.tv

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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.storage.FollowKind
import app.iptvplayer.testing.TestPanel
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.settings.SettingsTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
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

    @After
    fun forget() {
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

    private fun openSection(section: Section) {
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000) { focusedTag()?.startsWith("rail-") == true }
        walkTabsTo(
            section,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(ShellTags.rail(section))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
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
        press(KeyEvent.KEYCODE_BACK) // the keyboard takes the first Back; the prompt stays
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

        rule.onNodeWithTag(card).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Follow Example City FC")).fetchSemanticsNodes().isNotEmpty() }
        shot("r4-match-menu")
        rule.onAllNodes(hasText("Follow Example City FC"))[0].performClick()
        rule.waitUntil(10_000) { runBlocking { graph.isFollowed(FollowKind.TEAM, "9065") } }
        assertEquals(listOf("Example City FC"), runBlocking { graph.follows() }.map { it.name })
    }
}
