package app.iptvplayer.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelNamesTest {
    private fun check(raw: String, name: String, quality: String? = null) =
        assertEquals(ChannelNames.Display(name, quality), ChannelNames.display(raw), "cleaning \"$raw\"")

    @Test
    fun aCountryPrefixAndAQualityWordBecomeABadge() {
        check("LEB | LBC International HD", "LBC International", "HD")
        check("Lb| MTV Lebanon 4K", "MTV Lebanon", "4K")
        check("ES: LA SEXTA HEVC", "LA SEXTA")
        check("[NF] Netflix Action", "Netflix Action")
        check("|AR| MBC 1 FHD", "MBC 1", "FHD")
        check("UK | BBC One UHD HEVC", "BBC One", "4K")
        check("BEIN SPORTS 1 4K", "BEIN SPORTS 1", "4K")
        // Capitals before a colon are read as a prefix: providers write "ES: LA SEXTA", and a channel's own name rarely does.
        check("FR: TF1", "TF1")
    }

    @Test
    fun namesThatLookLikePrefixesOrQualityKeepTheirName() {
        check("beIN SPORT NEWS", "beIN SPORT NEWS")
        check("CBS Sports HQ", "CBS Sports HQ")
        check("Sky News", "Sky News")
        check("HD", "HD")
        check("Al Jazeera English", "Al Jazeera English")
        check("LBC", "LBC")
    }

    @Test
    fun nothingIsLeftBlank() {
        check("ES |", "ES")
        check("[NF]", "[NF]")
        check("|AR| HD", "HD")
    }
}
