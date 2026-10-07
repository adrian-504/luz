package app.iptvplayer.domain.library

/**
 * A channel's name as a viewer should read it. Providers pack a country or group into the name ("LEB | LBC International
 * HD", "ES: LA SEXTA HEVC", "[NF] Netflix Action") and the picture quality after it; the viewer wants "LBC International".
 * The same conservative rule as [TitleCleaner]: a prefix goes only when it is unmistakably a prefix — a short code before
 * a pipe or a colon, or in brackets — and a trailing word goes only when it is one of a short list of quality words, so
 * "CBS Sports HQ" and "Sky News" keep their names. Never throws; an unrecognisable name comes back unchanged.
 */
public object ChannelNames {
    /** [name] is what to show; [quality] is "HD", "4K" and the like, for a badge. */
    public data class Display(public val name: String, public val quality: String?)

    public fun display(raw: String): Display {
        var text = raw.trim()
        repeat(MAX_PREFIXES) {
            val match = PREFIX.find(text) ?: return@repeat
            if (match.range.last + 1 < text.length) text = text.substring(match.range.last + 1).trim()
        }
        var quality: String? = null
        while (true) {
            val words = text.split(' ')
            if (words.size < 2) break
            val last = words.last().trim(*SEPARATORS)
            val found = QUALITY[last.uppercase()] ?: break
            quality = quality ?: found.takeIf { it.isNotEmpty() }
            text = words.dropLast(1).joinToString(" ").trimEnd(*SEPARATORS)
        }
        val name = text.replace(SPACES, " ").trim(*SEPARATORS).ifBlank { raw.trim() }
        return Display(name, quality)
    }

    private const val MAX_PREFIXES = 2
    private val SEPARATORS = charArrayOf(' ', '-', '|', ':', '.', '_', '–', '—')
    private val SPACES = Regex("""\s+""")

    /** "LEB | ", "Lb|", "|AR| ", "[NF] ", and "ES: " — a colon only after capitals, which a name's own colon rarely follows. */
    private val PREFIX = Regex("""^\s*(?:\|\s*[A-Za-z0-9+]{2,6}\s*\||\[\s*[A-Za-z0-9+ ]{1,8}\s*]|[A-Za-z]{2,5}\s*\||[A-Z]{2,4}\s*:)\s*""")

    /** The words providers add to a channel's name for its picture; an empty value is removed without a badge. */
    private val QUALITY = mapOf(
        "HD" to "HD", "FHD" to "FHD", "UHD" to "4K", "4K" to "4K", "8K" to "8K", "SD" to "SD", "1080P" to "FHD", "720P" to "HD",
        "HEVC" to "", "H265" to "", "H.265" to "", "RAW" to "", "50FPS" to "", "60FPS" to "",
    )
}
