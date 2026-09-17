package app.iptvplayer.domain.library

/** The picture quality a provider wrote into a title. */
public enum class Quality { UHD, FHD, HD, SD }

/**
 * A provider's title taken apart: the name a viewer should read, and what was packed around it.
 *
 * [title] is what to show; [year] came from the title when the provider put it there; [quality], [tags] (HDR, Dolby
 * Vision, 3D, multiple languages, subtitled) and [language] (a two-letter code such as "EN") become small badges instead
 * of noise in the name. [workKey] identifies the film or show itself, so versions of the same work can be shown as one.
 */
public data class CleanTitle(
    public val title: String,
    public val year: Int?,
    public val quality: Quality?,
    public val tags: List<String>,
    public val language: String?,
) {
    public val workKey: String get() = TitleCleaner.workKey(title, year)
}

/**
 * Cleans the titles IPTV providers send (docs/DOMAIN_MODEL.md, "Titles").
 *
 * Providers pack routing and quality information into the name — `EN | The Batman (2022) 4K`, `|FR| Le Film [HDR]`,
 * `4K-NF - Movie MULTI` — because their own apps have nowhere else to put it. Luz shows the name alone and turns the rest
 * into badges. The rules are deliberately conservative: a word is only removed when it is unmistakably a tag, so a film
 * called `2012`, `Up` or `HD Radio` keeps its title. Never throws; an unrecognisable title comes back unchanged.
 */
public object TitleCleaner {
    public fun clean(raw: String): CleanTitle {
        var text = raw.trim()
        var language: String? = null
        val tags = linkedSetOf<String>()
        var quality: Quality? = null

        // 1. A prefix before a separator: "EN | ", "|EN| ", "[EN] ", "EN: ", "4K-EN - ", "NF | ".
        repeat(MAX_PREFIXES) {
            val prefix = prefixOf(text) ?: return@repeat
            prefix.codes.forEach { upper ->
                when {
                    upper in LANGUAGES -> language = language ?: LANGUAGES.getValue(upper)
                    else -> qualityOf(upper)?.let { quality = better(quality, it) } ?: tagOf(upper)?.let { tags += it }
                }
            }
            text = text.substring(prefix.length).trim()
        }

        // 2. Bracketed tags anywhere: "[4K]", "(HDR)", "[MULTI]"; a bracketed year is taken as the year.
        var year: Int? = null
        text = BRACKETED.replace(text) { match ->
            val inner = match.groupValues[2].trim()
            val upper = inner.uppercase()
            val asYear = inner.toIntOrNull()?.takeIf { it in YEARS }
            when {
                asYear == null && !looksLikeTag(inner) -> match.value
                asYear != null -> {
                    year = year ?: asYear
                    " "
                }
                qualityOf(upper) != null -> {
                    quality = better(quality, qualityOf(upper)!!)
                    " "
                }
                tagOf(upper) != null -> {
                    tags += tagOf(upper)!!
                    " "
                }
                upper in LANGUAGES -> {
                    language = language ?: LANGUAGES.getValue(upper)
                    " "
                }
                else -> match.value
            }
        }.let(::collapse)

        // 3. Trailing words that are tags: "Movie 4K HDR MULTI". Stops at the first ordinary word.
        while (true) {
            val words = text.split(' ')
            if (words.size < 2) break
            val last = words.last().trim(*TRIM)
            if (!looksLikeTag(last)) break
            val upper = last.uppercase()
            val q = qualityOf(upper)
            val t = tagOf(upper)
            when {
                q != null -> quality = better(quality, q)
                t != null -> tags += t
                else -> break
            }
            text = words.dropLast(1).joinToString(" ").trimEnd(*TRIM)
        }

        // 4. A year after a dash: "Movie - 2022". A bare trailing number is not taken: "Blade Runner 2049" and
        // "Wonder Woman 1984" are names. Bracketed years were taken in step 2.
        TRAILING_YEAR.find(text)?.let { match ->
            val candidate = match.groupValues[2].toInt()
            val rest = text.substring(0, match.range.first).trimEnd(*TRIM)
            if (candidate in YEARS && rest.isNotBlank()) {
                year = year ?: candidate
                text = rest
            }
        }

        val title = collapse(text).trim(*TRIM).ifBlank { raw.trim() }
        return CleanTitle(title, year, quality, tags.toList(), language)
    }

    /**
     * The work behind a title, for grouping versions: case, accents, punctuation and articles do not count, and the year
     * does when there is one ("Dune (1984)" and "Dune (2021)" stay apart).
     */
    public fun workKey(title: String, year: Int?): String {
        val simplified = title.lowercase()
            .map { FOLD[it] ?: it }
            .joinToString("")
            .replace(NON_WORD, " ")
            .split(' ')
            .filter { it.isNotBlank() && it !in ARTICLES }
            .joinToString(" ")
        return if (year != null) "$simplified|$year" else simplified
    }

    private class Prefix(val codes: List<String>, val length: Int)

    /**
     * The prefix at the start of [text], if there is one. Codes the provider marked unmistakably — between pipes, in
     * brackets, or before a pipe — may be anything short and upper-case (a source such as "NF"). Before a colon or a dash
     * only known codes count, so "AI: Artificial Intelligence" and "Spider-Man: No Way Home" keep their names.
     */
    private fun prefixOf(text: String): Prefix? {
        val wrapped = WRAPPED_PREFIX.find(text)
        val separated = SEPARATED_PREFIX.find(text)
        val (match, codeText, marked) = when {
            wrapped != null -> Triple(wrapped, wrapped.groupValues[1].ifEmpty { wrapped.groupValues[2] }, true)
            separated != null -> Triple(separated, separated.groupValues[1], separated.groupValues[2] == "|")
            else -> return null
        }
        // A prefix is never the whole title.
        if (match.range.last + 1 >= text.length) return null
        val codes = codeText.split('-', '/', ' ').filter { it.isNotBlank() }
        if (codes.isEmpty()) return null
        val known = codes.all { known(it.uppercase()) }
        val shortUpper = codes.all { it.length in 2..4 && it == it.uppercase() && it.any(Char::isLetterOrDigit) }
        if (!known && !(marked && shortUpper)) return null
        return Prefix(codes.map { it.uppercase() }, match.range.last + 1)
    }

    /** Providers write tags in capitals; "Dual" or "The Sub" in ordinary case are words of a title. */
    private fun looksLikeTag(word: String): Boolean = word.none { it.isLowerCase() } || RESOLUTION.matches(word)

    private fun known(upper: String): Boolean = upper in LANGUAGES || qualityOf(upper) != null || tagOf(upper) != null

    private fun qualityOf(upper: String): Quality? = when (upper) {
        "4K", "UHD", "2160P", "4K UHD" -> Quality.UHD
        "FHD", "1080P", "FULLHD", "FULL HD" -> Quality.FHD
        "HD", "720P", "HQ" -> Quality.HD
        "SD", "480P", "576P" -> Quality.SD
        else -> null
    }

    private fun tagOf(upper: String): String? = when (upper) {
        "HDR", "HDR10", "HDR10+" -> "HDR"
        "DV", "DOVI", "DOLBY VISION" -> "Dolby Vision"
        "3D" -> "3D"
        "MULTI", "MULTISUB", "MULTI-SUB", "MULTIAUDIO", "DUAL" -> "Multi-language"
        "VOSTFR", "VOST", "SUB", "SUBBED", "SUBS", "VOSE" -> "Subtitled"
        "IMAX" -> "IMAX"
        else -> null
    }

    private fun better(current: Quality?, found: Quality): Quality = if (current == null ||
        found.ordinal < current.ordinal
    ) {
        found
    } else {
        current
    }

    private fun collapse(text: String): String = text.replace(SPACES, " ").trim()

    private const val MAX_PREFIXES = 3
    private val YEARS = 1900..2100
    private val TRIM = charArrayOf(' ', '-', '|', ':', '.', '_', '–', '—')

    private val WRAPPED_PREFIX = Regex("""^\s*(?:\|\s*([A-Za-z0-9+/ -]{2,12}?)\s*\||\[\s*([A-Za-z0-9+/ -]{2,12}?)\s*])\s*""")
    private val SEPARATED_PREFIX = Regex("""^\s*([A-Za-z0-9+/-]{2,12})\s*([|:–—]|\s-)\s+""")

    private val BRACKETED = Regex("""([\[(])\s*([^\[\]()]{1,20})\s*([])])""")
    private val TRAILING_YEAR = Regex("""(\s[-–—]\s*)((?:19|20)\d{2})\s*$""")
    private val RESOLUTION = Regex("""\d{3,4}[pP]""")
    private val SPACES = Regex("""\s+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val ARTICLES = setOf("the", "a", "an", "le", "la", "les", "l", "el", "los", "las", "der", "die", "das", "il", "lo")
    private val FOLD = mapOf(
        'à' to 'a', 'á' to 'a', 'â' to 'a', 'ä' to 'a', 'ã' to 'a', 'å' to 'a', 'ç' to 'c', 'è' to 'e', 'é' to 'e', 'ê' to 'e',
        'ë' to 'e', 'ì' to 'i', 'í' to 'i', 'î' to 'i', 'ï' to 'i', 'ñ' to 'n', 'ò' to 'o', 'ó' to 'o', 'ô' to 'o', 'ö' to 'o',
        'õ' to 'o', 'ù' to 'u', 'ú' to 'u', 'û' to 'u', 'ü' to 'u', 'ÿ' to 'y', 'ß' to 's',
    )

    /** Language codes providers use as prefixes, mapped to the code shown on the badge. */
    private val LANGUAGES = mapOf(
        "EN" to "EN", "ENG" to "EN", "UK" to "EN", "US" to "EN", "FR" to "FR", "FRE" to "FR", "VF" to "FR", "VFF" to "FR",
        "DE" to "DE", "GER" to "DE", "ES" to "ES", "SPA" to "ES", "LAT" to "ES", "IT" to "IT", "ITA" to "IT", "PT" to "PT",
        "BR" to "PT", "NL" to "NL", "PL" to "PL", "TR" to "TR", "AR" to "AR", "RU" to "RU", "SE" to "SV", "SV" to "SV",
        "NO" to "NO", "DK" to "DA", "FI" to "FI", "GR" to "EL", "IN" to "HI", "HI" to "HI", "JP" to "JA", "KR" to "KO",
    )
}
