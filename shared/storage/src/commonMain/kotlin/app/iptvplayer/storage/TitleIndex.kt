package app.iptvplayer.storage

import app.iptvplayer.domain.text.SearchFold

/**
 * Turns what someone types into an FTS5 query for the `title_search` index (FR-SRCH-001).
 *
 * Every token is quoted, so punctuation a provider puts in a title ("24/7", "S1:E2", "AND") can never be read as FTS syntax,
 * and every token gets a `*` so results appear while typing. Tokens are combined with AND: "bein sp" matches "beIN Sports"
 * but not "beIN Movies".
 */
internal object TitleIndex {
    /** The MATCH expression for [query], or null when it holds nothing searchable. */
    fun match(query: String): String? = query.split(SEPARATORS)
        .filter { it.isNotEmpty() }
        .ifEmpty { return null }
        .joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"*" }

    /**
     * The MATCH expression for [query] against the title and its folded spellings (ADR-0055): each typed word matches
     * its own spelling, folded, or (three letters and more) its sound key; words are combined with AND.
     */
    fun matchFolded(query: String): String? {
        val words = SearchFold.spellings(query).ifEmpty { return null }
        // Only `title` and `folded` are indexed, so no column filter is needed; groups are joined with an explicit AND.
        return words.joinToString(" AND ") { word ->
            val plain = "\"" + word.replace("\"", "\"\"") + "\"*"
            // A sound key of one consonant ("two" is "t") would match half the list as a prefix: spelling only then.
            val key = SearchFold.soundKey(word)?.takeIf { it.length >= MIN_SOUND_KEY }?.let { "\"" + SearchFold.SOUND_MARK + it + "\"*" }
            if (key == null) plain else "($plain OR $key)"
        }
    }

    private const val MIN_SOUND_KEY = 2

    /** Only letters and digits are indexed (unicode61), so everything else separates tokens. */
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
}
