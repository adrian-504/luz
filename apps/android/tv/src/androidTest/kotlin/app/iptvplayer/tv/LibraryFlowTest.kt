package app.iptvplayer.tv

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
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
import app.iptvplayer.testing.TestArtwork
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.library.ArtworkTags
import app.iptvplayer.tv.ui.library.Browse
import app.iptvplayer.tv.ui.library.DetailTags
import app.iptvplayer.tv.ui.library.HomeTags
import app.iptvplayer.tv.ui.library.LibraryTags
import app.iptvplayer.tv.ui.library.PersonTags
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.player.EpisodesSheetTags
import app.iptvplayer.tv.ui.player.PlayerTags
import app.iptvplayer.tv.ui.shell.Section
import app.iptvplayer.tv.ui.shell.ShellTags
import app.iptvplayer.tv.ui.theme.LuzMenuTags
import coil3.SingletonImageLoader
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
import kotlin.time.Duration.Companion.minutes
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
    fun openingSearchDoesNotRaiseTheKeyboardButOkOnTheFieldDoes() {
        openSection(Section.SEARCH, SearchTags.FIELD)
        // The letter strip is how Luz types; the system keyboard covers the results, so it stays away until asked for.
        Thread.sleep(1_500)
        rule.waitForIdle()
        assertFalse("the keyboard came up on its own when Search opened", keyboardShown())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(5_000) { keyboardShown() }
    }

    /** The phone companion (ADR-0050): a phone pairs with the code, types into Search and presses keys; strangers get nothing. */
    @Test
    fun aPairedPhoneTypesIntoSearchAndMovesTheRemote() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            openSection(Section.SEARCH, SearchTags.FIELD)
            assertEquals(401, companionCall("/api/key", """{"token":"made-up","key":"down"}""").first)
            assertEquals(403, companionCall("/api/pair", """{"code":"WRONG234","name":"Test"}""").first)
            assertEquals(421, companionCall("/api/key", """{"key":"down"}""", host = "luz.example.com").first)
            val paired = companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""")
            assertEquals(200, paired.first)
            val token = JSONObject(paired.second).getString("token")

            rule.waitUntil(5_000) { CompanionHub.field.value != null }
            val field = CompanionHub.field.value!!.id
            assertEquals(200, companionCall("/api/text", """{"token":"$token","field":$field,"text":"test"}""").first)
            rule.waitUntil(5_000) {
                rule.onNodeWithTag(SearchTags.FIELD).fetchSemanticsNode().config[SemanticsProperties.EditableText].text == "test"
            }
            assertEquals(200, companionCall("/api/key", """{"token":"$token","key":"down"}""").first)
            rule.waitUntil(5_000) { focusedTag() != SearchTags.FIELD }
        } finally {
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
    }

    /** Films and series on the phone (2c, Design 2.16): the TV's shelves and categories, and a poster grid page by page. */
    @Test
    fun aPairedPhoneBrowsesFilmsAndSeries() {
        val companion = graph.companion
        companion.setOn(true)
        try {
            val token = JSONObject(
                companionCall("/api/pair", """{"code":"${companion.pairing.code()}","name":"Test phone"}""").second,
            ).getString("token")
            assertEquals(401, companionGet("/api/library?kind=movie", "wrong").first)

            val films = JSONObject(companionGet("/api/library?kind=movie", token).second)
            assertEquals("movie", films.getString("kind"))
            assertTrue("the library holds films", films.getLong("total") > 0)
            assertTrue("the TV's shelves", films.getJSONArray("shelves").length() > 0)
            val categories = films.getJSONArray("categories")
            assertTrue(categories.length() > 0)

            val all = JSONObject(companionGet("/api/browse?kind=movie&filter=all&offset=0&limit=2", token).second)
            assertTrue("a page holds at most the limit", all.getJSONArray("items").length() in 1..2)
            assertEquals(films.getLong("total"), all.getLong("total"))
            val first = all.getJSONArray("items").getJSONObject(0)
            assertEquals("movie", first.getString("kind"))
            assertTrue(first.has("art") && first.has("fraction"))

            val category = categories.getJSONObject(0)
            // Encoded as the phone's page encodes it (encodeURIComponent).
            val filter = android.net.Uri.encode("category:${category.getString("id")}")
            val answer = companionGet("/api/browse?kind=movie&filter=$filter&offset=0&limit=60", token)
            assertEquals("category $category: ${answer.second}", 200, answer.first)
            assertEquals(category.getString("title"), JSONObject(answer.second).getString("title"))
            assertEquals(404, companionGet("/api/browse?kind=movie&filter=category%3Agone", token).first)
            assertEquals(404, companionGet("/api/library?kind=book", token).first)

            val series = JSONObject(companionGet("/api/library?kind=series", token).second)
            assertTrue(series.getLong("total") > 0)
        } finally {
            companion.pairing.phones.value.forEach { companion.pairing.forget(it.id) }
            companion.setOn(false)
        }
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

    /** Arabic search (ADR-0055, Design 4.2): "ع" swaps the strip to Arabic letters, and an Arabic query finds the channel. */
    @Test
    fun arabicLettersTypeAnArabicQueryAndFindTheChannel() {
        val channel = runBlocking { graph.channels(playlist, null) }.single { it.name.endsWith("Example") }
        openSection(Section.SEARCH, SearchTags.FIELD)
        if (keyboardShown()) press(KeyEvent.KEYCODE_BACK)
        // Down reaches the strip in the middle; Left walks to its first key, the script key.
        repeat(3) { if (focusedTag()?.startsWith("search-key") != true) press(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(40) { if (focusedTag() != SearchTags.key("script")) press(KeyEvent.KEYCODE_DPAD_LEFT) }
        awaitFocus(SearchTags.key("script"))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        // The strip now holds Arabic letters: alef is one of its keys.
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag(SearchTags.key("\u0627"))).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        screenshot("r4-arabic-strip")
        // Right to left (Design 4.2): from alef, Right lands on the key that is to its right on screen, the space key.
        repeat(6) { if (focusedTag() != SearchTags.key("\u0627")) press(KeyEvent.KEYCODE_DPAD_LEFT) }
        awaitFocus(SearchTags.key("\u0627"))
        screenshot("r4-arabic-strip-alef")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(SearchTags.key("space"))
        screenshot("r4-arabic-strip-right")
        rule.onNodeWithTag(SearchTags.FIELD).performTextInput("\u0642\u0646\u0627\u0629")
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag(SearchTags.result("channel", channel.id.value))).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Whether the card for [movieId] in the search results shows its title on the picture's place (the stand-in). */
    private fun standInShown(movieId: String): Boolean = rule.onAllNodes(
        hasTestTag(ArtworkTags.STAND_IN).and(hasAnyAncestor(hasTestTag(SearchTags.result("movie", movieId)))),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    private fun searchForMovie(title: String): String {
        val movie = runBlocking { graph.movies(playlist, null, 10, 0) }.single { it.title == title }
        // Nothing from an earlier test may already hold this poster, or it would not load at all.
        SingletonImageLoader.get(rule.activity).run {
            memoryCache?.clear()
            diskCache?.clear()
        }
        openSection(Section.SEARCH, SearchTags.FIELD)
        rule.onNodeWithTag(SearchTags.FIELD).performTextInput(title.lowercase())
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(SearchTags.result("movie", movie.id))).fetchSemanticsNodes().isNotEmpty() }
        return movie.id
    }

    /** A poster loads on a plain shape (ADR-0051, Design 1.3): the title is under the card already, not on the picture. */
    @Test
    fun posterShowsNoTitleWhileItsPictureLoads() {
        TestArtwork.slow = setOf("Test Movie Two")
        try {
            val id = searchForMovie("Test Movie Two")
            // The server holds the picture back a second; during it, the card shows no words where the picture goes.
            repeat(5) {
                assertFalse(standInShown(id))
                Thread.sleep(150)
            }
        } finally {
            TestArtwork.slow = emptySet()
        }
    }

    /** A picture that fails leaves the title on the card's place, so the card is never an empty shape. */
    @Test
    fun aPictureThatFailsShowsTheTitle() {
        TestArtwork.missing = setOf("Test Movie Two")
        try {
            val id = searchForMovie("Test Movie Two")
            rule.waitUntil(10_000) { standInShown(id) }
        } finally {
            TestArtwork.missing = emptySet()
        }
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
        // Under the page: the other test film shares its actor and genre, so it is "More like this".
        assertEquals(listOf(two.id), runBlocking { graph.movieShelves(playlist, one.id, emptySet(), 10) }.moreLikeThis.map { it.id })
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
        // Home opens on its first row, "Continue watching", with the remote on the movie: OK resumes it.
        awaitFocus(HomeTags.item(HomeTags.CONTINUE, movie.id), timeout = 20_000)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()
    }

    /** Down during an episode (5.21): the sheet opens on Episodes on the playing one; OK on another plays it. */
    @Test
    fun downDuringAnEpisodePlaysAnotherFromTheSheet() {
        val series = runBlocking { graph.series(playlist, null, 10, 0) }.single()
        val episodes = runBlocking { graph.episodes(playlist, series.id) }.ifEmpty {
            // Episodes are read when the series page opens.
            emptyList()
        }
        openSection(Section.SERIES, entry = null)
        openFullGrid()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(LibraryTags.item(series.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitText(DetailTags.PLAY, "S1 E1")
        awaitFocus(DetailTags.PLAY)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()
        val all = episodes.ifEmpty { runBlocking { graph.episodes(playlist, series.id) } }
        val first = all.first { it.seasonNumber == 1 && it.episodeNumber == 1 }
        val second = all.first { it.seasonNumber == 1 && it.episodeNumber == 2 }

        press(KeyEvent.KEYCODE_BACK)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(EpisodesSheetTags.SHEET)).fetchSemanticsNodes().isNotEmpty() }
        awaitFocus(EpisodesSheetTags.episode(first.id))
        rule.waitForIdle()
        screenshot("r5-episodes-sheet")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitFocus(EpisodesSheetTags.episode(second.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(EpisodesSheetTags.SHEET)).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(20_000) { CompanionHub.playing.value?.subtitle?.startsWith("S1 E2") == true }
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

        // "Continue watching" has one card for the series, on the episode that follows the one finished (ADR-0042).
        val card = runBlocking { graph.continueCards(playlist, 10) }.single()
        assertEquals(series.id, card.pageId)
        assertEquals("S1 E2", card.subtitle)

        // Home: a long press on that card can take it off the row.
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(LibraryTags.item(series.id))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(LibraryTags.tile(Browse.ALL))
        press(KeyEvent.KEYCODE_BACK)
        awaitFocus(ShellTags.rail(Section.SERIES))
        walkTabsTo(
            Section.HOME,
            ::focusedTag,
            { key -> press(key) },
        ) { timeout, condition -> runCatching { rule.waitUntil(timeout, condition) } }
        awaitFocus(ShellTags.rail(Section.HOME))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitFocus(HomeTags.item(HomeTags.CONTINUE, series.id), timeout = 20_000)
        longPressOk()
        awaitFocus(LuzMenuTags.item("open"))
        pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, LuzMenuTags.item("remove-continue"), times = 4)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(10_000) { runBlocking { graph.continueCards(playlist, 10) }.isEmpty() }
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasTestTag(HomeTags.item(HomeTags.CONTINUE, series.id))).fetchSemanticsNodes().isEmpty()
        }
        // Its progress is kept: the series' page still knows where the viewer is.
        assertEquals(true, runBlocking { graph.episode(playlist, first.id) }?.progress?.completed)
    }

    @Test
    fun myLuzGathersWhatTheViewerKeptAndIsPartWayThrough() {
        val movie = runBlocking { graph.movies(playlist, null, 10, 0) }.first { it.title == "Test Movie One" }
        runBlocking {
            graph.setFavorite(ContentType.MOVIE, movie.id, true)
            graph.saveProgress(playlist, ContentType.MOVIE, movie.id, null, 12.seconds, 30.seconds, ended = false, newSession = true)
        }
        // My Luz is the old Favorites place in the navigation: Continue watching first, then My List, with the remote on the
        // first card.
        openSection(Section.FAVORITES, entry = HomeTags.item(HomeTags.CONTINUE, movie.id))
        pressUntilFocused(KeyEvent.KEYCODE_DPAD_DOWN, HomeTags.item(HomeTags.MY_LIST, movie.id), times = 3)
        // OK on a card in My List plays it, resuming where it was left.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()
    }

    @Test
    fun aPausedFilmShowsTheCardAndOkCarriesOn() {
        val movie = runBlocking { graph.movies(playlist, null, 10, 0) }.first { it.title == "Test Movie One" }
        runBlocking {
            graph.saveProgress(playlist, ContentType.MOVIE, movie.id, null, 12.seconds, 30.seconds, ended = false, newSession = true)
        }
        openSection(Section.FAVORITES, entry = HomeTags.item(HomeTags.CONTINUE, movie.id))
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitPlaying()

        // Paused and left alone, the controls give way to the pause card (ADR-0046).
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)
        rule.waitUntil(20_000) { rule.onAllNodes(hasTestTag(PlayerTags.PAUSE_CARD)).fetchSemanticsNodes().isNotEmpty() }
        // OK on the card carries on: the card goes and nothing else takes its place.
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(PlayerTags.PAUSE_CARD)).fetchSemanticsNodes().isEmpty() }
        assertTrue(rule.onAllNodes(hasTestTag(PlayerTags.OVERLAY)).fetchSemanticsNodes().isEmpty())
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
}

private const val MENU_SETTLE_MS = 300L
