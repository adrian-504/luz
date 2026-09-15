package app.iptvplayer.domain.security

/** RFC 3986 percent-encoding helpers shared by URL templating, redaction and protocol parsers. */
public object PercentEncoding {
    private const val HEX_UPPER = "0123456789ABCDEF"

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    /** RFC 3986: keep unreserved characters, percent-encode every other UTF-8 byte (uppercase hex). */
    public fun encode(value: String, spaceAsPlus: Boolean = false): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xff
            val c = b.toChar()
            when {
                b < 0x80 && isUnreserved(c) -> append(c)
                spaceAsPlus && b == 0x20 -> append('+')
                else -> append('%').append(HEX_UPPER[b ushr 4]).append(HEX_UPPER[b and 0x0f])
            }
        }
    }

    /** Decodes %XX sequences (and optionally '+'); malformed sequences are kept literally. */
    public fun decode(value: String, plusAsSpace: Boolean = false): String {
        if ('%' !in value && !(plusAsSpace && '+' in value)) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hi = if (c == '%' && i + 2 <= value.lastIndex) value[i + 1].digitToIntOrNull(16) else null
            val lo = if (hi != null) value[i + 2].digitToIntOrNull(16) else null
            when {
                hi != null && lo != null -> {
                    bytes.add(((hi shl 4) or lo).toByte())
                    i += 3
                }
                plusAsSpace && c == '+' -> {
                    bytes.add(0x20)
                    i++
                }
                else -> {
                    // Copy a whole surrogate pair at once so supplementary characters survive.
                    val end = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) i + 2 else i + 1
                    value.substring(i, end).encodeToByteArray().forEach { bytes.add(it) }
                    i = end
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}
