package app.iptvplayer.domain.security

import app.iptvplayer.domain.net.ParsedUrl

/**
 * Removes credentials and credential-bearing URL parts from text before it reaches logs, diagnostics or
 * exports (docs/SECURITY.md §4.2). Structural rules always apply; [knownSecrets] adds exact-value redaction.
 *
 * An instance with [pseudonymizeHosts] keeps a host→alias table and is meant for one export; it is not
 * thread-safe.
 */
public class Redactor(knownSecrets: Collection<String> = emptyList(), private val pseudonymizeHosts: Boolean = false) {
    private val secretForms: List<String> = knownSecrets
        .filter { it.length >= MIN_SECRET_LENGTH }
        .flatMap {
            listOf(
                it,
                PercentEncoding.encode(it),
                PercentEncoding.encode(it).lowercaseHex(),
                PercentEncoding.encode(it, spaceAsPlus = true),
            )
        }
        .distinct()
        .sortedByDescending { it.length }

    private val hostAliases = LinkedHashMap<String, String>()

    /** Redacts every URL found in [text], sensitive key/value pairs, and all known secret values. */
    public fun redactText(text: String): String {
        val out = StringBuilder(text.length)
        var cursor = 0
        var search = text.indexOf("://")
        while (search >= 0) {
            var start = search
            while (start > cursor && text[start - 1].isSchemeChar()) start--
            if (start < search && text[start].isAsciiLetter()) {
                var end = search + 3
                while (end < text.length && !text[end].isUrlTerminator()) end++
                out.append(text, cursor, start).append(redactUrl(text.substring(start, end)))
                cursor = end
                search = text.indexOf("://", end)
            } else {
                search = text.indexOf("://", search + 3)
            }
        }
        out.append(text, cursor, text.length)
        val structural = KEY_VALUE.replace(out.toString()) { it.groupValues[1] + MARK }
        return replaceSecrets(structural)
    }

    /** Structural URL redaction: drops userinfo, masks Xtream credential path segments and sensitive query values. */
    public fun redactUrl(url: String): String {
        val parsed = ParsedUrl.parse(url) ?: return replaceSecrets(redactXtreamPath(url))
        if (!parsed.hasAuthority) return replaceSecrets(url)
        val host = if (pseudonymizeHosts) alias(parsed.host) else parsed.hostAndPort
        val rebuilt = buildString {
            append(parsed.scheme).append("://").append(host)
            append(redactXtreamPath(parsed.path))
            parsed.query?.let { append('?').append(redactQuery(it)) }
            parsed.fragment?.let { append('#').append(it) }
        }
        return replaceSecrets(rebuilt)
    }

    public fun redactHeader(name: String, value: String): String =
        if (name.lowercase() in SENSITIVE_HEADERS) MARK else replaceSecrets(value)

    private fun alias(host: String): String = hostAliases.getOrPut(host) { "host-${hostAliases.size + 1}" }

    private fun replaceSecrets(text: String): String {
        var result = text
        for (form in secretForms) result = result.replace(form, MARK)
        return result
    }

    private fun redactQuery(query: String): String = query.split('&').joinToString("&") { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) return@joinToString pair
        val name = PercentEncoding.decode(pair.substring(0, eq), plusAsSpace = true).lowercase()
        if (name in SENSITIVE_QUERY_PARAMETERS) pair.substring(0, eq + 1) + MARK else pair
    }

    public companion object {
        public const val MARK: String = "‹redacted›"

        /** Shorter secrets are not substring-redacted (too many false positives); structural rules still apply. */
        public const val MIN_SECRET_LENGTH: Int = 3

        public val SENSITIVE_QUERY_PARAMETERS: Set<String> = setOf(
            "username", "user", "password", "pass", "pwd", "token", "auth", "key", "apikey", "api_key",
            "signature", "sig", "session", "sid", "hash", "expires", "e", "st",
        )

        public val SENSITIVE_HEADERS: Set<String> = setOf("authorization", "cookie", "set-cookie", "x-api-key", "proxy-authorization")

        /** Structural rules only. */
        public val STRUCTURAL: Redactor = Redactor()

        /** `scheme://host[:port]/‹redacted›` — nothing after the authority survives. */
        public fun origin(url: String): String {
            val parsed = ParsedUrl.parse(url)
            if (parsed == null || !parsed.hasAuthority) return MARK
            val hasPath = parsed.path.isNotEmpty() && parsed.path != "/"
            val rest = hasPath || parsed.query != null || parsed.fragment != null
            return "${parsed.scheme}://${parsed.hostAndPort}" + if (rest) "/$MARK" else ""
        }

        private val XTREAM_KINDS = setOf("live", "movie", "series", "timeshift")

        /** `"password": "x"`, `password=x`, `'token': x` in free text (e.g. echoed Xtream JSON in exception messages). */
        private val KEY_VALUE = Regex("""(?i)(["']?\b(?:username|password|passwd|pwd|pass|token)["']?\s*[:=]\s*["']?)[^"'&\s,;}]+""")

        /** `/live/<u>/<p>/<id>` → `/live/{u}/{p}/<id>`; requires at least one segment after the password. */
        internal fun redactXtreamPath(path: String): String {
            val segments = path.split('/').toMutableList()
            for (i in segments.indices) {
                if (segments[i] in XTREAM_KINDS && i + 3 <= segments.lastIndex) {
                    segments[i + 1] = "{u}"
                    segments[i + 2] = "{p}"
                    break
                }
            }
            return segments.joinToString("/")
        }

        private fun String.lowercaseHex(): String {
            val chars = toCharArray()
            for (i in chars.indices) {
                val afterPercent = (i > 0 && chars[i - 1] == '%') || (i > 1 && chars[i - 2] == '%')
                if (afterPercent) chars[i] = chars[i].lowercaseChar()
            }
            return chars.concatToString()
        }

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

        private fun Char.isSchemeChar(): Boolean = isAsciiLetter() || this in '0'..'9' || this == '+' || this == '.' || this == '-'

        private fun Char.isUrlTerminator(): Boolean = this.isWhitespace() || this in "\"'<>|`"
    }
}
