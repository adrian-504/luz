package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
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
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.onboarding.FormTags
import app.iptvplayer.tv.ui.settings.SettingsTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import app.iptvplayer.tv.ui.sources.SourcesTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * A custom guide link from Playlists (FR-SRC-004) — the owner's provider has an empty XMLTV guide and no short EPG. The
 * synthetic panel's own `xmltv.php` link stands in for a third-party guide, so the link also carries (canary) credentials.
 */
@RunWith(AndroidJUnit4::class)
class GuideLinkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph
    private val provider = DeveloperStreams.testProvider(instrumentation.targetContext)!!

    private val source = object : ExternalResource() {
        override fun before() {
            val (server, username, password) = provider
            runBlocking { assertTrue(graph.addXtream("Guide link test", server, username, password) is AddSourceResult.Added) }
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(source).around(rule)

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

    @Test
    fun guideLinkIsSavedFromSettingsAndTheGuideDownloadsFromIt() {
        val playlist = runBlocking { graph.sources().single().playlistId }
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.LIVE_TV))
        walkTabsTo(Section.SETTINGS, ::focusedTag, { key -> press(key) }) { timeout, condition ->
            runCatching {
                rule.waitUntil(timeout, condition)
            }
        }
        awaitFocus(ShellTags.rail(Section.SETTINGS))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(SettingsTags.entry(SettingsTags.PROVIDERS))
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(SettingsTags.ADD_SOURCE)
        repeat(6) { if (focusedTag() != SourcesTags.guideLink(playlist)) press(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT) }
        awaitFocus(SourcesTags.guideLink(playlist))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(FormTags.URL)

        val (server, username, password) = provider
        rule.onNodeWithTag(FormTags.URL).performTextInput("${server.trimEnd('/')}/xmltv.php?username=$username&password=$password")
        rule.onNodeWithTag(FormTags.SUBMIT).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.awaitKeyboardReleased()
        awaitFocus(FormTags.SUBMIT)
        rule.pressOkUntil { runBlocking { graph.hasGuideLink(playlist) } }

        // Back in Playlists; the guide downloads from the link and the source says so.
        awaitFocus(SourcesTags.guideLink(playlist))
        assertTrue(runBlocking { graph.hasGuideLink(playlist) })
        rule.waitUntil(30_000) {
            runBlocking { graph.guideState(playlist) }?.let { it.status == ImportStatus.PUBLISHED && it.itemCount > 0 } == true
        }
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag(SourcesTags.guide(playlist)).and(hasText("your guide link", substring = true)))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Reopening shows that a link is set and offers the provider's guide again.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(FormTags.GUIDE_LINK_SET)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(FormTags.GUIDE_LINK_CLEAR).performSemanticsAction(SemanticsActions.RequestFocus)
        awaitFocus(FormTags.GUIDE_LINK_CLEAR)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(SourcesTags.guideLink(playlist))
        rule.waitUntil(10_000) { !runBlocking { graph.hasGuideLink(playlist) } }
    }
}
