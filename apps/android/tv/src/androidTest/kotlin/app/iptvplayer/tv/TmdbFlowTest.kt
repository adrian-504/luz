package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.testing.TestPanel
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.library.HomeTags
import app.iptvplayer.tv.ui.settings.SettingsTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import app.iptvplayer.tv.ui.theme.LuzPromptTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * TMDB with the viewer's own key (ADR-0038), against the in-app test server's fake of TMDB: the key is entered in Settings,
 * checked, kept, the lists are read, and Home gains a Trending row made of the library's own films in TMDB's order.
 */
@RunWith(AndroidJUnit4::class)
class TmdbFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    private val provider = object : ExternalResource() {
        override fun before() {
            DeveloperStreams.useTestTmdb = true
            runBlocking { graph.removeTmdbKey() }
            val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
            runBlocking {
                val added = graph.addXtream("TMDB test", server, username, password)
                assertTrue("$added", added is AddSourceResult.Added)
                val playlist = (added as AddSourceResult.Added).playlistId
                val deadline = System.nanoTime() + 30_000_000_000L
                while (graph.libraryState(playlist, ImportUnit.MOVIES)?.status != ImportStatus.PUBLISHED) {
                    check(System.nanoTime() < deadline) { "library not imported" }
                    Thread.sleep(100)
                }
            }
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(provider).around(rule)

    @After
    fun forget() {
        runBlocking { graph.removeTmdbKey() }
        DeveloperStreams.useTestTmdb = false
    }

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

    @Test
    fun aKeyEnteredInSettingsBringsTrendingFilmsFromTheLibrary() {
        openSection(Section.SETTINGS)
        awaitFocus(SettingsTags.entry(SettingsTags.PROVIDERS))
        repeat(6) { if (focusedTag() != SettingsTags.entry(SettingsTags.TMDB)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(SettingsTags.entry(SettingsTags.TMDB))
        press(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(SettingsTags.TMDB_KEY)
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(LuzPromptTags.PROMPT)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(LuzPromptTags.FIELD).performTextInput(TestPanel.TMDB_KEY)
        press(KeyEvent.KEYCODE_BACK) // the keyboard takes the first Back; the prompt stays
        repeat(4) { if (focusedTag() != LuzPromptTags.CONFIRM) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(LuzPromptTags.CONFIRM)
        rule.pressOkUntil(timeoutMs = 10_000) { rule.onAllNodes(hasTestTag(LuzPromptTags.PROMPT)).fetchSemanticsNodes().isEmpty() }

        // Checked, kept, and the lists read: Settings says so, and the Trending row holds the test films in TMDB's order.
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasTestTag(SettingsTags.TMDB).and(hasText("Connected", substring = true))).fetchSemanticsNodes().isNotEmpty()
        }
        val trending = runBlocking { graph.moviesOfList(playlist, ExternalList.TRENDING_MOVIES, 10) }.map { it.title }
        assertEquals(listOf("Test Movie Two", "Test Movie One"), trending)
        assertTrue(runBlocking { graph.tmdbStatus() }.hasKey)
        val film = runBlocking { graph.moviesOfList(playlist, ExternalList.TRENDING_MOVIES, 1) }.single()

        // Home: the Trending row, under the hero, starts with that film.
        press(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000) { focusedTag()?.startsWith("rail-") == true }
        walkTabsTo(
            Section.HOME,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(ShellTags.rail(Section.HOME))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(HomeTags.HERO_PLAY, timeout = 20_000)
        repeat(4) { if (focusedTag()?.startsWith("home-${HomeTags.TRENDING_MOVIES}-") != true) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(HomeTags.item(HomeTags.TRENDING_MOVIES, film.id))
    }

    /** Artwork for what the viewer opens (ADR-0039): a logo and portraits for a film, a biography for a person, and none of it when switched off. */
    @Test
    fun openedTitlesAndPeopleGetTheirArtworkUnlessSwitchedOff() {
        runBlocking {
            assertEquals(null, graph.setTmdbKey(TestPanel.TMDB_KEY))
            graph.setTmdbArtwork(true)
            val art = graph.titleArt(ContentType.MOVIE, "Test Movie One", 2021, null)
            assertEquals("https://image.tmdb.org/t/p/w500/test-logo.png", art?.logoUrl)
            assertEquals(setOf("Alex Example"), graph.portraits(listOf("Alex Example", "Sam Placeholder")).keys)
            assertEquals("A synthetic actor.", graph.personArt("Alex Example")?.biography)

            graph.setTmdbArtwork(false)
            assertEquals(null, graph.titleArt(ContentType.MOVIE, "Test Movie One", 2021, null))
            assertTrue(graph.portraits(listOf("Alex Example")).isEmpty())
            graph.setTmdbArtwork(true)
        }
    }
}
