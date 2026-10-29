package app.iptvplayer.domain.live

/**
 * The News tab on the channel strip (s0): news channels in three runs, Arabic, French and English. A channel is news
 * when its name or its category holds a news word as a whole word; its language comes from the provider's prefix, Arabic
 * letters in its name, or its category's prefix. A channel whose language cannot be told is left out, never guessed.
 * No list of channel names: nothing to keep up to date.
 */
public object NewsChannels {
    public enum class Language { ARABIC, FRENCH, ENGLISH }

    /** One channel to judge: its id, name and category name, and how often it was watched (more first). */
    public data class Candidate(public val id: String, public val name: String, public val group: String?, public val watched: Int = 0)

    /** The news channels by language, each run most watched first, then in the order given. */
    public fun classify(channels: List<Candidate>): Map<Language, List<String>> {
        val found = channels.withIndex().mapNotNull { (index, channel) ->
            if (!isNews(channel.name) && !isNews(channel.group.orEmpty())) return@mapNotNull null
            val language = languageOf(channel.name) ?: channel.group?.let(::languageOf) ?: return@mapNotNull null
            Triple(language, channel, index)
        }
        return Language.entries.associateWith { language ->
            found.filter { it.first == language }
                .sortedWith(compareByDescending<Triple<Language, Candidate, Int>> { it.second.watched }.thenBy { it.third })
                .map { it.second.id }
        }.filterValues { it.isNotEmpty() }
    }

    /** Whether a name holds a news word as a whole word ("Infinity" does not). */
    public fun isNewsWord(text: String): Boolean = isNews(text)

    private fun isNews(text: String): Boolean {
        val lower = text.lowercase()
        return WORDS.any { word -> Regex("""(^|[^\p{L}])${Regex.escape(word)}($|[^\p{L}])""").containsMatchIn(lower) }
    }

    private fun languageOf(text: String): Language? {
        if (text.any { it in '\u0600'..'\u06FF' }) return Language.ARABIC
        val prefix = PREFIX.find(text.trim())?.groupValues?.get(1)?.uppercase() ?: return null
        return when (prefix) {
            in ARABIC -> Language.ARABIC
            in FRENCH -> Language.FRENCH
            in ENGLISH -> Language.ENGLISH
            else -> null
        }
    }

    private val WORDS = listOf("news", "info", "infos", "actualité", "actualités", "actualite", "actualites", "أخبار", "اخبار")

    /** "AR| …", "|FR| …", "UK: …", "[US] …": a short code before a separator, as providers write it. */
    private val PREFIX = Regex("""^[|\[]?\s*([A-Za-z]{2,3})\s*[|:\]]""")
    private val ARABIC =
        setOf("AR", "ARA", "LB", "LEB", "SA", "KSA", "AE", "UAE", "QA", "KW", "EG", "MA", "DZ", "TN", "JO", "IQ", "OM", "BH")
    private val FRENCH = setOf("FR", "FRA", "BE", "CH")
    private val ENGLISH = setOf("UK", "GB", "US", "USA", "EN", "ENG", "CA", "AU", "IE")
}
