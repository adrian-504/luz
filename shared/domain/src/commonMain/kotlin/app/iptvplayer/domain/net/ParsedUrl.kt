package app.iptvplayer.domain.net

/**
 * Minimal URL splitter (scheme, userinfo, host, port, path, query, fragment). Components keep their original
 * encoding; only scheme and ASCII host characters are lowercased. Not a general URI library.
 */
public class ParsedUrl internal constructor(
    public val scheme: String,
    public val hasAuthority: Boolean,
    public val userInfo: String?,
    public val host: String,
    public val isIpv6Literal: Boolean,
    public val port: Int?,
    public val path: String,
    public val query: String?,
    public val fragment: String?,
) {
    /** Authority without userinfo: `host[:port]` with IPv6 brackets restored. */
    public val hostAndPort: String
        get() = (if (isIpv6Literal) "[$host]" else host) + (port?.let { ":$it" } ?: "")

    public fun toUrlString(includeUserInfo: Boolean = true): String = buildString {
        append(scheme).append(':')
        if (hasAuthority) {
            append("//")
            if (includeUserInfo && userInfo != null) append(userInfo).append('@')
            append(hostAndPort)
        }
        append(path)
        if (query != null) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    }

    /** Deliberately does not print the URL: it may contain credentials. */
    override fun toString(): String = "ParsedUrl(scheme=$scheme)"

    public companion object {
        /** Returns null when the input is not a syntactically valid absolute URL. */
        public fun parse(raw: String): ParsedUrl? {
            val colon = raw.indexOf(':')
            if (colon <= 0) return null
            val scheme = raw.substring(0, colon)
            if (!scheme[0].isAsciiLetter() || !scheme.all { it.isAsciiLetter() || it.isAsciiDigit() || it in "+.-" }) return null
            val rest = raw.substring(colon + 1)
            if (!rest.startsWith("//")) {
                return ParsedUrl(scheme.lowercase(), false, null, "", false, null, rest, null, null)
            }
            val afterSlashes = rest.substring(2)
            val delimiter = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authorityEnd = if (delimiter < 0) afterSlashes.length else delimiter
            val authority = afterSlashes.substring(0, authorityEnd)
            val remainder = afterSlashes.substring(authorityEnd)

            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = authority.substring(at + 1)

            val host: String
            val portText: String?
            val ipv6: Boolean
            if (hostPort.startsWith('[')) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                host = hostPort.substring(1, close)
                val after = hostPort.substring(close + 1)
                portText = when {
                    after.isEmpty() -> null
                    after.startsWith(':') -> after.substring(1)
                    else -> return null
                }
                ipv6 = true
            } else {
                val portColon = hostPort.lastIndexOf(':')
                host = if (portColon >= 0) hostPort.substring(0, portColon) else hostPort
                portText = if (portColon >= 0) hostPort.substring(portColon + 1) else null
                ipv6 = false
            }
            val hostValid = if (ipv6) {
                host.isNotEmpty() && host.all { it.isAsciiDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
            } else {
                host.none { it in "[]:@/\\" }
            }
            if (!hostValid) return null
            val port = when {
                portText.isNullOrEmpty() -> null
                portText.length > 5 || !portText.all { it.isAsciiDigit() } -> return null
                else -> portText.toInt().takeIf { it in 0..65535 } ?: return null
            }

            val queryStart = remainder.indexOf('?')
            val fragmentStart = remainder.indexOf('#')
            val pathEnd = listOf(queryStart, fragmentStart).filter { it >= 0 }.minOrNull() ?: remainder.length
            val path = remainder.substring(0, pathEnd)
            val query = if (queryStart >= 0 && (fragmentStart < 0 || queryStart < fragmentStart)) {
                remainder.substring(queryStart + 1, if (fragmentStart >= 0) fragmentStart else remainder.length)
            } else {
                null
            }
            val fragment = if (fragmentStart >= 0) remainder.substring(fragmentStart + 1) else null

            return ParsedUrl(scheme.lowercase(), true, userInfo, lowercaseAscii(host), ipv6, port, path, query, fragment)
        }

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

        private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

        private fun lowercaseAscii(s: String): String =
            buildString(s.length) { for (c in s) append(if (c in 'A'..'Z') c + ('a' - 'A') else c) }
    }
}
