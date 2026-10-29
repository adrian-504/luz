package app.iptvplayer.domain.text

/**
 * Arabic that reads right (x13): whether a line is mostly Arabic, and joining mixed parts so a line such as
 * "S1 E3 · <Arabic title>" or "21:00 · <Arabic name>" never scrambles when the two directions meet.
 */
public object ArabicText {
    /** True when more than half the letters are Arabic (the Arabic, Arabic Supplement and presentation-form blocks). */
    public fun isArabic(text: String): Boolean {
        var arabic = 0
        var letters = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            if (c in '\u0600'..'\u06FF' || c in '\u0750'..'\u077F' || c in '\uFB50'..'\uFEFF') arabic++
        }
        return letters > 0 && arabic * 2 > letters
    }

    /** The parts joined with " · ", each wrapped in first-strong isolates so its own direction cannot spill over. */
    public fun joinFacts(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString(SEPARATOR) { "$FIRST_STRONG_ISOLATE$it$POP_ISOLATE" }

    private const val SEPARATOR = " " + "\u00B7" + " "
    private const val FIRST_STRONG_ISOLATE = '\u2068'
    private const val POP_ISOLATE = '\u2069'
}
