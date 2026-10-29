package app.iptvplayer.domain.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArabicTextTest {
    @Test
    fun aMostlyArabicLineIsArabic() {
        assertTrue(ArabicText.isArabic("\u0627\u0644\u062C\u062F\u064A\u062F"))
        assertTrue(ArabicText.isArabic("\u0627\u0644\u062C\u062F\u064A\u062F TV"))
        assertFalse(ArabicText.isArabic("Al Jadeed"))
        assertFalse(ArabicText.isArabic("21:00"))
    }

    @Test
    fun factsAreIsolatedAndJoined() {
        val joined = ArabicText.joinFacts("21:00", "\u0627\u0644\u062C\u062F\u064A\u062F", null, " ")
        assertEquals("\u206821:00\u2069 \u00B7 \u2068\u0627\u0644\u062C\u062F\u064A\u062F\u2069", joined)
    }
}
