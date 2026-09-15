package app.iptvplayer.protocols.sniff

public enum class SniffedFormat { M3U, HLS_PLAYLIST, XMLTV, XML_OTHER, JSON, HTML, GZIP, EMPTY, UNKNOWN }

/**
 * Identifies a downloaded body from its first bytes, independent of file extension or Content-Type
 * (docs/IPTV_PROTOCOLS.md §6 VALIDATE).
 */
public object ContentSniffer {
    public const val SNIFF_BYTES: Int = 8 * 1024

    private val HLS_TAGS = listOf(
        "#EXT-X-TARGETDURATION",
        "#EXT-X-STREAM-INF",
        "#EXT-X-MEDIA-SEQUENCE",
        "#EXT-X-PLAYLIST-TYPE",
        "#EXT-X-ENDLIST",
        "#EXT-X-MAP",
        "#EXT-X-KEY",
        "#EXT-X-VERSION",
    )

    public fun sniff(prefix: ByteArray): SniffedFormat {
        if (prefix.size >= 2 && prefix[0] == 0x1F.toByte() && prefix[1] == 0x8B.toByte()) return SniffedFormat.GZIP
        val text = prefix.decodeToString().removePrefix("\uFEFF").trimStart()
        if (text.isEmpty()) return SniffedFormat.EMPTY
        val upper = text.uppercase()
        return when {
            upper.startsWith("#EXTM3U") || upper.startsWith("#EXTINF") ->
                if (HLS_TAGS.any { upper.contains(it) }) SniffedFormat.HLS_PLAYLIST else SniffedFormat.M3U
            upper.startsWith("{") || upper.startsWith("[") -> SniffedFormat.JSON
            upper.startsWith("<!DOCTYPE HTML") || upper.startsWith("<HTML") || upper.contains("<HTML") -> SniffedFormat.HTML
            upper.startsWith("<") -> if (upper.contains("<TV")) SniffedFormat.XMLTV else SniffedFormat.XML_OTHER
            else -> SniffedFormat.UNKNOWN
        }
    }
}
