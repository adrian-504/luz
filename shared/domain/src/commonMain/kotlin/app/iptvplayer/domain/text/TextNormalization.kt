package app.iptvplayer.domain.text

/**
 * Deterministic text normalization used for stable IDs and matching (docs/DOMAIN_MODEL.md §4.3, §4.5).
 *
 * The rules are mirrored exactly by the reference implementation in `tooling/scripts/generate_id_vectors.py`;
 * any change here must change both and regenerate `tooling/fixtures/ids/id-vectors.json`.
 */
public object TextNormalization {
    private val QUALITY_TOKENS = setOf(
        "sd", "hd", "fhd", "uhd", "qhd", "4k", "8k", "hdr", "hevc", "h264", "h265", "720p", "1080p", "1080i", "2160p",
    )

    /** Collapses whitespace runs (explicit set, see [isNormWhitespace]) to one space and trims; case and characters kept. */
    public fun collapse(input: String): String = tokens(input).joinToString(" ")

    /** NFKC → lowercase (invariant) → collapse whitespace runs to one space → trim. Keeps quality tags. */
    public fun normKey(input: String): String = tokens(nfkc(input).lowercase()).joinToString(" ")

    /**
     * Aggressive normalization for matching only, never for primary keys: [normKey], then strip a short country
     * prefix (`uk:`, `|fr|`), bracketed segments, punctuation/symbols and quality tokens. Falls back to [normKey]
     * with punctuation removed when nothing would remain.
     */
    public fun matchNormalize(input: String): String {
        val key = normKey(input)
        val kept = tokens(replaceSeparators(removeBracketed(stripCountryPrefix(key)))).filterNot { it in QUALITY_TOKENS }
        if (kept.isNotEmpty()) return kept.joinToString(" ")
        return tokens(replaceSeparators(key)).joinToString(" ")
    }

    /** Explicit whitespace set so every platform agrees: Zs, Zl, Zp plus ASCII/C1 whitespace controls. */
    internal fun isNormWhitespace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', '\u0085' -> true
        else -> when (c.category) {
            CharCategory.SPACE_SEPARATOR, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR -> true
            else -> false
        }
    }

    private fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (c in s) {
            if (isNormWhitespace(c)) {
                if (current.isNotEmpty()) {
                    out.add(current.toString())
                    current.clear()
                }
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    private fun trimNormWhitespace(s: String): String = s.trim { isNormWhitespace(it) }

    private fun isAsciiLower(s: String): Boolean = s.all { it in 'a'..'z' }

    private fun stripCountryPrefix(key: String): String {
        if (key.startsWith('|')) {
            val close = key.indexOf('|', startIndex = 1)
            return if (close in 3..4 && isAsciiLower(key.substring(1, close))) trimNormWhitespace(key.substring(close + 1)) else key
        }
        for (length in 2..3) {
            if (key.length > length && isAsciiLower(key.substring(0, length)) && (key[length] == ':' || key[length] == '|')) {
                return trimNormWhitespace(key.substring(length + 1))
            }
        }
        return key
    }

    private fun removeBracketed(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val close = when (s[i]) {
                '(' -> ')'
                '[' -> ']'
                '{' -> '}'
                else -> null
            }
            if (close != null) {
                val end = s.indexOf(close, i + 1)
                if (end >= 0) {
                    out.append(' ')
                    i = end + 1
                    continue
                }
            }
            out.append(s[i])
            i++
        }
        return out.toString()
    }

    /** Punctuation (P*), symbols (S*) and any supplementary-plane code point become spaces. */
    private fun replaceSeparators(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) out.append(if (isSeparator(c)) ' ' else c)
        return out.toString()
    }

    private fun isSeparator(c: Char): Boolean = c.isSurrogate() || when (c.category) {
        CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION,
        CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
        CharCategory.OTHER_PUNCTUATION, CharCategory.MATH_SYMBOL, CharCategory.CURRENCY_SYMBOL,
        CharCategory.MODIFIER_SYMBOL, CharCategory.OTHER_SYMBOL,
        -> true
        else -> false
    }
}

/** Unicode Normalization Form KC using the platform's Unicode tables. */
internal expect fun nfkc(input: String): String
