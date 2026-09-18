package app.iptvplayer.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals

class TitleCleanerTest {
    private fun check(
        raw: String,
        title: String,
        year: Int? = null,
        quality: Quality? = null,
        tags: List<String> = emptyList(),
        language: String? = null,
    ) {
        assertEquals(CleanTitle(title, year, quality, tags, language), TitleCleaner.clean(raw), "cleaning \"$raw\"")
    }

    @Test
    fun providerPrefixesBecomeALanguageAndBadges() {
        check("EN | The Batman (2022) 4K", "The Batman", 2022, Quality.UHD, language = "EN")
        check("|FR| Le Film [HDR]", "Le Film", tags = listOf("HDR"), language = "FR")
        check("[EN] Dune (2021)", "Dune", 2021, language = "EN")
        check("4K-EN - The Batman", "The Batman", quality = Quality.UHD, language = "EN")
        check("EN: Oppenheimer 4K HDR", "Oppenheimer", quality = Quality.UHD, tags = listOf("HDR"), language = "EN")
        check("US | Movie", "Movie", language = "EN")
    }

    @Test
    fun anUnknownSourceCodeIsRemovedOnlyWhenTheProviderMarkedItAsAPrefix() {
        check("NF | Stranger Things", "Stranger Things")
        check("|AMZ| The Boys", "The Boys")
        check("AI: Artificial Intelligence", "AI: Artificial Intelligence")
        check("JFK - The Book of the Dead", "JFK - The Book of the Dead")
    }

    @Test
    fun trailingTagsAndYearsAreTakenOff() {
        check("The Matrix 1080p MULTI", "The Matrix", quality = Quality.FHD, tags = listOf("Multi-language"))
        check("Movie - 2019", "Movie", 2019)
        check("Spider-Man: No Way Home (2021)", "Spider-Man: No Way Home", 2021)
        check("Wonder Woman 1984 (2020)", "Wonder Woman 1984", 2020)
    }

    @Test
    fun namesThatLookLikeTagsKeepTheirTitle() {
        check("Blade Runner 2049", "Blade Runner 2049")
        kotlin.test.assertEquals("Blade Runner 2049", TitleCleaner.clean("Blade Runner 2049", knownYear = 2017).title)
        kotlin.test.assertEquals("It Follows", TitleCleaner.clean("It Follows 2014", knownYear = 2014).title, "the provider's own year")
        kotlin.test.assertEquals("2012", TitleCleaner.clean("2012", knownYear = 2012).title, "a title that is only the year keeps it")
        check("2012", "2012")
        check("Up", "Up")
        check("Dual", "Dual")
        check("The Sub", "The Sub")
        check("1917", "1917")
    }

    @Test
    fun theBestQualityWinsWhenSeveralAreWritten() {
        check("EN | Movie HD [4K]", "Movie", quality = Quality.UHD, language = "EN")
    }

    @Test
    fun theWorkKeyIgnoresCaseAccentsPunctuationAndArticlesButNotTheYear() {
        assertEquals(TitleCleaner.workKey("The Batman", 2022), TitleCleaner.workKey("batman", 2022))
        assertEquals(TitleCleaner.workKey("Amélie", null), TitleCleaner.workKey("Amelie", null))
        assertEquals(TitleCleaner.workKey("Spider-Man: No Way Home", 2021), TitleCleaner.workKey("Spider Man No Way Home", 2021))
        check(TitleCleaner.workKey("Dune", 1984) != TitleCleaner.workKey("Dune", 2021))
    }

    private fun check(condition: Boolean) = kotlin.test.assertTrue(condition)
}
