package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.library.Browse
import app.iptvplayer.tv.ui.library.DetailTags
import app.iptvplayer.tv.ui.library.HomeTags
import app.iptvplayer.tv.ui.library.LibraryTags
import app.iptvplayer.tv.ui.library.PersonTags
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.player.PlayerTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

/** Movies and series with the remote (ROADMAP Phase 8): browse, detail, play, resume, next episode. */
@RunWith(AndroidJUnit4::class)
class LibraryFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as IptvApplication).graph

    /** Adds the synthetic provider and waits until its movies and series are imported in the background. */
    private val provider = object : ExternalResource() {
        override fun before() {
            val (server, username, password) = DeveloperStreams.testProvider(instrumentation.targetContext)!!
            runBlocking {
                val added = graph.addXtream("Library test", server, username, password)
                assertTrue("$added", added is AddSourceResult.Added)
                val playlist = (added as AddSourceResult.Added).playlistId
                val deadline = System.nanoTime() + 30.seconds.inWholeNanoseconds
                while (listOf(
                        ImportUnit.MOVIES,
                        ImportUnit.SERIES,
                    ).any { graph.libraryState(playlist, it)?.status != ImportStatus.PUBLISHED }
                ) {
                    check(System.nanoTime() < deadline) { "library not imported" }
                    Thread.sleep(100)
                }
            }
        }
    }

    val rule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(NoSourcesRule()).around(provider).around(rule)

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
        // Any node with the tag: a row being recomposed can briefly hold two nodes with the same tag.
        runCatching { rule.waitUntil(timeout) { rule.onAllNodes(hasTestTag(tag).and(isFocused())).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("expected focus on $tag, but focused: ${focusedTag()}", it) }
    }

    private fun awaitText(tag: String, text: String, timeout: Long = 20_000) {
        runCatching {
            rule.waitUntil(timeout) {
                rule.onAllNodes(
                    hasTestTag(tag).and(hasText(text, substring = true)),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty() ||
                    rule.onAllNodes(hasTestTag(tag).and(hasText(text, substring = true))).fetchSemanticsNodes().isNotEmpty()
            }
        }.onFailure { throw AssertionError("expected '$text' in $tag; focused: ${focusedTag()}", it) }
    }

    private fun keyboardShown(): Boolean {
        var shown = false
        instrumentation.runOnMainSync {
            shown = androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        return shown
    }

    private fun awaitPlaying() = awaitText(PlayerTags.STATE, rule.activity.getString(R.string.player_state_playing))

    /** Presses [keyCode] until [tag] has focus, at most [times] times. */
    private fun pressUntilFocused(keyCode: Int, tag: String, times: Int = 12) {
        repeat(times) {
            if (focusedTag() == tag) return
            press(keyCode)
        }
        awaitFocus(tag)
    }

    /** From a section's shelves, "Browse all categories" → "All", on the grid of every title. */
    private fun openFullGrid() {
        rule.waitUntil(20_000) { focusedTag()?.startsWith("library-") == true }
        pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, LibraryTags.tile(Browse.ALL))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(LibraryTags.CATEGORY_ALL)
    }

    private fun openSection(section: Section, entry: String? = LibraryTags.CATEGORY_ALL) {
        rule.waitUntil(20_000) { focusedTag()?.startsWith("live-") == true }
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.LIVE_TV))
        walkTabsTo(
            section,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(ShellTags.rail(section))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        entry?.let { awaitFocus(it) }
    }

    @Test
    fun searchFindsChannelsMoviesAndSeriesAsYouType() {
        val channel = runBlocking { graph.channels(playlist, null) }.first()
        val movie = runBlocking { graph.movies(playlist, null, 10, 0) }.first()
        val series = runBlocking { graph.series(playlist, null, 10, 0) }.single()
        openSection(Section.SEARCH, SearchTags.FIELD)

        rule.onNodeWithTag(SearchTags.FIELD).performTextInput("test")
        // Channels and movies rows are on screen; the series row is further down (lazy list), so it is checked in the results.
        rule.waitUntil(10_000) {
            listOf(SearchTags.result("channel", channel.id.value), SearchTags.result("movie", movie.id))
                .all { tag -> rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(runBlocking { graph.search(playlist, "test") }.series.any { it.id == series.id })
        rule.onNodeWithTag(SearchTags.FIELD).performTextClearance()
        rule.onNodeWithTag(SearchTags.FIELD).performTextInput("movie two")
        rule.waitUntil(
            10_000,
        ) { rule.onAllNodes(hasTestTag(SearchTags.result("channel", channel.id.value))).fetchSemanticsNodes().isEmpty() }
        val two = runBlocking { graph.movies(playlist, null, 10, 0) }.single { it.title == "Test Movie Two" }
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(SearchTags.result("movie", two.id))).fetchSemanticsNodes().isNotEmpty() }

        // As on a TV: Back closes the on-screen keyboard (it takes the first Back), Down reaches the result, OK opens the movie.
        if (keyboardShown()) {
            press(KeyEvent.KEYCODE_BACK)
            rule.waitUntil(5_000) { !keyboardShown() }
        }
        awaitFocus(SearchTags.FIELD)
        // Down passes the letter strip on its way to the results.
        repeat(3) { if (focusedTag() != SearchTags.result("movie", two.id)) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        awaitFocus(SearchTags.result("movie", two.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(DetailTags.PLAY)
    }

    @Test
    fun aFilmPageShowsItsPeopleAndOpensEverythingOfTheirs() {
        val movies = runBlocking { graph.movies(playlist, null, 10, 0) }
        val one = movies.first { it.title == "Test Movie One" }
        val two = movies.first { it.title == "Test Movie Two" }
        // Movies opens on its shelves; the newest film is first on "Recently added".
        openSection(Section.MOVIES, LibraryTags.shelfItem("recent", one.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(DetailTags.PLAY)

        // The page is fetched on opening: director first, then the cast; the trailer button appears with it.
        val director = DetailTags.person("Jordan Sample", directed = true)
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(DetailTags.TRAILER)).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitFocus(director)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(DetailTags.person("Alex Example", directed = false))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        // Alex Example is in both test films.
        rule.waitUntil(10_000) {
            listOf(
                PersonTags.movie(one.id),
                PersonTags.movie(two.id),
            ).all { rule.onAllNodes(hasTestTag(it)).fetchSemanticsNodes().isNotEmpty() }
        }
        rule.waitUntil(10_000) { focusedTag()?.startsWith("person-movie-") == true }
        assertTrue(runBlocking { graph.search(playlist, "alex") }.people.any { it.name == "Alex Example" && it.titles == 2 })
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(DetailTags.person("Alex Example", directed = false))
    }

    @Test
    fun movieDetailPlaysAndOffersToResumeWhereYouLeft() {
        val movie = runBlocking { graph.movies(playlist, null, 10, 0) }.first { it.title == "Test Movie One" }
        // The full grid is still there, under "Browse all categories".
        openSection(Section.MOVIES, entry = null)
        openFullGrid()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LibraryTags.item(movie.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(DetailTags.PLAY)
        awaitText(DetailTags.PLAY, rule.activity.getString(R.string.detail_play))

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()
        // The bar shows the time played and the time left (ADR-0036).
        awaitText(PlayerTags.PROGRESS, "−0:")
        // Hide the overlay, then skip 20 s forward with Right (movies seek instead of switching channels).
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT)
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(PlayerTags.SEEK_HUD)).fetchSemanticsNodes().isNotEmpty() }
        press(KeyEvent.KEYCODE_BACK)

        // Back on the detail screen: the position was saved when the player closed.
        awaitFocus(DetailTags.PLAY)
        rule.waitUntil(10_000) { runBlocking { graph.movie(playlist, movie.id) }?.progress?.position?.let { it >= 15.seconds } == true }
        awaitText(DetailTags.PLAY, "Resume")
        rule.onNodeWithTag(DetailTags.PLAY_FROM_START).assertExists()
        assertTrue(runBlocking { graph.continueWatching(playlist, 10) }.any { it.type == ContentType.MOVIE && it.id == movie.id })

        // Home: the movie is first in "Continue watching", and OK resumes it.
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(LibraryTags.item(movie.id))
        // Back leaves the grid for the shelves, on the tile that opened it, then goes to the navigation.
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(LibraryTags.tile(Browse.ALL))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.MOVIES))
        walkTabsTo(
            Section.HOME,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(ShellTags.rail(Section.HOME))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // Home opens on its hero, which features what the viewer is part-way through first: its Play resumes the movie.
        awaitFocus(HomeTags.HERO_PLAY, timeout = 20_000)
        awaitText(HomeTags.HERO_PLAY, rule.activity.getString(R.string.home_hero_resume))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()
    }

    @Test
    fun seriesEpisodesLoadAndTheNextEpisodeFollowsTheEndOfOne() {
        val series = runBlocking { graph.series(playlist, null, 10, 0) }.single()
        openSection(Section.SERIES, entry = null)
        openFullGrid()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LibraryTags.item(series.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)

        // Episodes come from get_series_info when the series is opened; nothing watched yet: start with S1 E1.
        awaitText(DetailTags.PLAY, "S1 E1")
        awaitFocus(DetailTags.PLAY)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()

        // Skip to the end: the overlay returns with "Next: S1 E2" focused.
        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        awaitFocus(PlayerTags.NEXT, timeout = 20_000)
        awaitText(PlayerTags.NEXT, "S1 E2")
        val first = runBlocking { graph.episodes(playlist, series.id) }.first()
        rule.waitUntil(10_000) { runBlocking { graph.episode(playlist, first.id) }?.progress?.completed == true }

        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()
        press(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BACK)

        // The series now continues with episode 2.
        awaitText(DetailTags.PLAY, "S1 E2")
    }
}
