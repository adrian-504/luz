package app.iptvplayer.domain.live

/**
 * A provider's category name as Live TV shows it (Design 3.6, D2): one written all in capitals reads in sentence case,
 * "USA ENTERTAINMENT" as "USA entertainment"; words of three capitals or fewer stay as they are (USA, BBC, TV). Names
 * with any lower case, and Arabic or any script without case, are left alone.
 */
public object GroupNames {
    public fun display(raw: String): String {
        val letters = raw.filter { it.isLetter() }
        if (letters.isEmpty() || letters.any { it.isLowerCase() } || letters.none { it.isUpperCase() }) return raw
        var first = true
        return raw.split(' ').joinToString(" ") { word ->
            val caps = word.count { it.isUpperCase() }
            val out = when {
                caps in 1..SHORT_ACRONYM -> word
                first -> word.lowercase().replaceFirstChar { it.uppercase() }
                else -> word.lowercase()
            }
            if (word.any { it.isLetter() }) first = false
            out
        }
    }

    private const val SHORT_ACRONYM = 3
}
