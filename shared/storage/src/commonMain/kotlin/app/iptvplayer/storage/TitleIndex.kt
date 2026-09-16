package app.iptvplayer.storage

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

    /** Only letters and digits are indexed (unicode61), so everything else separates tokens. */
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
}
