package app.iptvplayer.domain.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SearchFoldTest {
    private fun key(word: String) = SearchFold.soundKey(SearchFold.spellings(word).single())

    @Test
    fun latinSpellingsOfOneNameShareASoundKey() {
        assertEquals(key("aljadeed"), key("jadid"))
        assertEquals(key("al-jadeed".replace("-", "")), key("jadeed"))
        assertEquals("jdd", key("jadid"))
    }

    @Test
    fun arabicAndLatinMeet() {
        assertEquals(key("jadeed"), key("\u0627\u0644\u062C\u062F\u064A\u062F"))
        assertEquals(key("jazeera"), key("\u0627\u0644\u062C\u0632\u064A\u0631\u0629"))
        assertEquals(key("hilal"), key("\u0627\u0644\u0647\u0644\u0627\u0644"))
    }

    @Test
    fun diacriticsAndAlefFormsFoldAway() {
        assertEquals(
            SearchFold.spellings("\u0627\u0644\u062C\u062F\u064A\u062F"),
            SearchFold.spellings("\u0627\u0644\u062C\u064E\u062F\u0650\u064A\u062F"),
        )
        assertEquals(SearchFold.spellings("\u0627\u062E\u0628\u0627\u0631"), SearchFold.spellings("\u0623\u062E\u0628\u0627\u0631"))
        assertEquals(listOf("12"), SearchFold.spellings("\u0661\u0662"))
    }

    @Test
    fun differentNamesStayApart() {
        assertNotEquals(key("ahed"), key("ahly"))
        assertNotEquals(key("nasr"), key("nejmeh"))
        assertNotEquals(key("\u0627\u0644\u0639\u0647\u062F"), key("\u0627\u0644\u0646\u0635\u0631"))
    }

    @Test
    fun soundKeysAreMarkedAndShortWordsHaveNone() {
        assertTrue(SearchFold.fold("Al Jadeed").split(" ").contains("~jdd"))
        assertEquals(null, SearchFold.soundKey("tv"))
    }

    private val sameName = listOf(
        "hilal" to "\u0627\u0644\u0647\u0644\u0627\u0644",
        "nasr" to "\u0627\u0644\u0646\u0635\u0631",
        "ahed" to "\u0627\u0644\u0639\u0647\u062F",
        "jazeera" to "\u0627\u0644\u062C\u0632\u064A\u0631\u0629",
        "jadeed" to "\u0627\u0644\u062C\u062F\u064A\u062F",
        "arabiya" to "\u0627\u0644\u0639\u0631\u0628\u064A\u0629",
        "mayadeen" to "\u0627\u0644\u0645\u064A\u0627\u062F\u064A\u0646",
        "manar" to "\u0627\u0644\u0645\u0646\u0627\u0631",
        "nour" to "\u0627\u0644\u0646\u0648\u0631",
        "shorouk" to "\u0627\u0644\u0634\u0631\u0648\u0642",
        "zamalek" to "\u0627\u0644\u0632\u0645\u0627\u0644\u0643",
        "riyadh" to "\u0627\u0644\u0631\u064A\u0627\u0636",
        "dubai" to "\u062F\u0628\u064A",
        "qatar" to "\u0642\u0637\u0631",
        "kuwait" to "\u0627\u0644\u0643\u0648\u064A\u062A",
        "bahrain" to "\u0627\u0644\u0628\u062D\u0631\u064A\u0646",
        "faisaly" to "\u0627\u0644\u0641\u064A\u0635\u0644\u064A",
        "wehda" to "\u0627\u0644\u0648\u062D\u062F\u0629",
    )

    private val differentNames = listOf(
        "ahed" to "ahly",
        "nasr" to "nejmeh",
        "hilal" to "ittihad",
        "jadeed" to "jazeera",
        "manar" to "nour",
        "riyadh" to "rotana",
        "dubai" to "abu dhabi",
        "qatar" to "kuwait",
        "zamalek" to "ahly",
        "shorouk" to "sharjah",
        "mayadeen" to "manar",
        "bahrain" to "bein",
        "faisaly" to "wehda",
        "lbc" to "mtv",
    )

    /** First word of each side: the name, as a channel or team is usually written. */
    private fun mainKey(text: String) = SearchFold.spellings(text).map { SearchFold.soundKey(it) }.firstOrNull { it != null }

    @Test
    fun arabicAndLatinNamesMeetAcrossTheList() {
        val missed = sameName.filter { (latin, arabic) -> mainKey(latin) != mainKey(arabic) }
        assertTrue(missed.isEmpty(), "should meet: " + missed.joinToString { (l, a) -> "$l=${mainKey(l)} / ${mainKey(a)}" })
    }

    /**
     * Accepted, harmless collisions: words from one root ("arabiya" and "arab"). Spelled-out letters ("MTV" written as
     * three Arabic letter names) cannot meet by sound and are left to the plain spelling.
     */
    @Test
    fun differentNamesStayApartAcrossTheList() {
        val met = differentNames.filter { (a, b) -> mainKey(a) == mainKey(b) }
        assertTrue(met.isEmpty(), "should stay apart: " + met.joinToString { (a, b) -> "$a/$b" })
    }
}
