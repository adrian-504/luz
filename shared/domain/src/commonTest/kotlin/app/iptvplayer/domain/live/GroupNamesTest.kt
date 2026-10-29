package app.iptvplayer.domain.live

import kotlin.test.Test
import kotlin.test.assertEquals

class GroupNamesTest {
    @Test
    fun allCapitalsBecomeSentenceCase() {
        assertEquals("Sports premium", GroupNames.display("SPORTS PREMIUM"))
    }

    @Test
    fun shortAcronymsStay() {
        assertEquals("USA entertainment", GroupNames.display("USA ENTERTAINMENT"))
        assertEquals("UK | BBC channels", GroupNames.display("UK | BBC CHANNELS"))
    }

    @Test
    fun arabicIsUntouched() {
        assertEquals("لبنان", GroupNames.display("لبنان"))
        // The Latin part of a two-script name follows the rule; the Arabic part has no case to change.
        assertEquals("Lebanon - لبنان", GroupNames.display("LEBANON - لبنان"))
    }

    @Test
    fun mixedCaseIsUntouched() {
        assertEquals("Documentaries & Kids", GroupNames.display("Documentaries & Kids"))
    }
}
