package app.iptvplayer.domain.text

/**
 * Search that folds Arabic and Latin spellings together (ADR-0055): "al jadeed", "aljadid", "jadid" and the Arabic spelling find
 * the same channel. Separate from [TextNormalization], which feeds stable ids and must not change.
 *
 * [fold] gives the words a title is searched by: each word spelt the plain way (lower case, Arabic diacritics and
 * tatweel removed, alef forms and teh marbuta unified, Arabic-Indic digits as ASCII), followed by its sound key, a
 * consonant skeleton after the article ("al", "el", "l'" or the Arabic one) is taken off, written with a leading `~` so
 * a sound key never matches a real word.
 */
public object SearchFold {
    /** The words to index or search for [text]: spellings, then sound keys of words of three letters or more. */
    public fun fold(text: String): String {
        val words = spellings(text)
        val keys = words.mapNotNull { soundKey(it) }.map { "$SOUND_MARK$it" }
        return (words + keys).distinct().joinToString(" ")
    }

    /** The plain-spelling words of [text]. */
    public fun spellings(text: String): List<String> {
        val out = StringBuilder()
        for (c in text.lowercase()) {
            when {
                c in DIACRITICS || c == TATWEEL -> Unit
                c in ALEF_FORMS -> out.append(ALEF)
                c == TEH_MARBUTA -> out.append(HEH)
                c == ALEF_MAKSURA || c == YEH_HAMZA -> out.append(YEH)
                c == WAW_HAMZA -> out.append(WAW)
                c in ARABIC_DIGITS -> out.append('0' + (c - ARABIC_DIGITS.first))
                c.isLetterOrDigit() -> out.append(c)
                else -> out.append(' ')
            }
        }
        return out.split(' ').filter { it.isNotEmpty() }
    }

    /** The sound key of one plain-spelling word, or null for a word too short to have one. */
    public fun soundKey(word: String): String? {
        val bare = withoutArticle(word)
        if (bare.length < MIN_SOUND_LETTERS) return null
        val arabic = bare.any { it in '\u0600'..'\u06FF' }
        // Letters doubled as written count once ("jaddid"); vowels dropped later never make a double ("jadid" is jdd).
        val single = collapse(bare)
        val key = (if (arabic) arabicSkeleton(single) else latinSkeleton(single)).let {
            // A silent final h, as teh marbuta reads: "Jazeera" and "Jazeerah" alike.
            if (it.length > 1 && it.endsWith('h') && !it.endsWith("sh") && !it.endsWith("kh") && !it.endsWith("gh") &&
                !it.endsWith("th")
            ) {
                it.dropLast(1)
            } else {
                it
            }
        }
        return key.takeIf { it.isNotEmpty() }
    }

    private fun withoutArticle(word: String): String = when {
        word.startsWith("\u0627\u0644") && word.length > 3 -> word.substring(2)
        word.startsWith("al") && word.length > 4 -> word.substring(2)
        word.startsWith("el") && word.length > 4 -> word.substring(2)
        word.startsWith("l'") && word.length > 3 -> word.substring(2)
        else -> word
    }

    private fun arabicSkeleton(word: String): String {
        val out = StringBuilder()
        word.forEachIndexed { index, c ->
            val inside = index in 1 until word.lastIndex
            when {
                c == WAW || c == YEH || c == ALEF -> if (!inside && index == 0) out.append(ARABIC_LETTERS[c].orEmpty())
                else -> out.append(ARABIC_LETTERS[c].orEmpty())
            }
        }
        return out.toString()
    }

    private fun latinSkeleton(word: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < word.length) {
            val pair = if (i + 1 < word.length) word.substring(i, i + 2) else ""
            if (pair in DIGRAPHS) {
                out.append(pair)
                i += 2
                continue
            }
            // "dh" is how names write both dal-like letters (Riyadh, Abu Dhabi): one sound with d.
            if (pair == "dh") {
                out.append('d')
                i += 2
                continue
            }
            // Qaf is written q or k (Shorouk, Iraqi): one sound.
            val c = word[i].let { if (it == 'q') 'k' else it }
            val inside = i > 0
            when {
                c.isDigit() -> out.append(c)
                c in VOWELS && inside -> Unit
                (c == 'y' || c == 'w') && inside -> Unit
                c in VOWELS -> Unit
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Letters doubled as written, once: "jadded" and "jaded" sound the same. */
    private fun collapse(key: String): String {
        val out = StringBuilder()
        for (c in key) if (out.isEmpty() || out.last() != c) out.append(c)
        return out.toString()
    }

    /** Sound keys carry this mark in the index and in queries, so `~jdd` never matches the word "jdd". */
    public const val SOUND_MARK: Char = '~'

    private const val MIN_SOUND_LETTERS = 3
    private const val TATWEEL = '\u0640'
    private const val ALEF = '\u0627'
    private const val TEH_MARBUTA = '\u0629'
    private const val HEH = '\u0647'
    private const val ALEF_MAKSURA = '\u0649'
    private const val YEH = '\u064A'
    private const val YEH_HAMZA = '\u0626'
    private const val WAW = '\u0648'
    private const val WAW_HAMZA = '\u0624'
    private val DIACRITICS = ('\u064B'..'\u065F').toSet() + '\u0670'
    private val ALEF_FORMS = setOf('\u0623', '\u0625', '\u0622', '\u0671')
    private val ARABIC_DIGITS = '\u0660'..'\u0669'
    private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')
    private val DIGRAPHS = setOf("sh", "kh", "gh", "th")

    /**
     * Arabic letters as the Latin consonants people write them with; ain and hamza are dropped. Qaf and kaf share k, and
     * thal and dad share d with dal, as Latin names write them (ADR-0055).
     */
    private val ARABIC_LETTERS: Map<Char, String> = mapOf(
        '\u0628' to "b", '\u062A' to "t", '\u062B' to "th", '\u062C' to "j", '\u062D' to "h", '\u062E' to "kh",
        '\u062F' to "d", '\u0630' to "d", '\u0631' to "r", '\u0632' to "z", '\u0633' to "s", '\u0634' to "sh",
        '\u0635' to "s", '\u0636' to "d", '\u0637' to "t", '\u0638' to "z", '\u0639' to "", '\u063A' to "gh",
        '\u0641' to "f", '\u0642' to "k", '\u0643' to "k", '\u0644' to "l", '\u0645' to "m", '\u0646' to "n",
        '\u0647' to "h", '\u0648' to "w", '\u064A' to "y", '\u0621' to "", '\u0627' to "",
    )
}
