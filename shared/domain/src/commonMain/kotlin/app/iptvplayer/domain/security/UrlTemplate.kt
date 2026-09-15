package app.iptvplayer.domain.security

import app.iptvplayer.domain.net.ParsedUrl

/**
 * A URL safe to persist: known credential values are replaced by placeholders (ADR-0015). Expanded to a
 * [SensitiveUrl] only in memory, right before a request or playback.
 *
 * Only whole components are templated — userinfo parts, whole path segments and whole query values — so a
 * username such as `live` never corrupts unrelated parts of the URL.
 */
public class UrlTemplate(public val template: String) {
    public val hasCredentialPlaceholders: Boolean
        get() = USERNAME in template || PASSWORD in template

    /** Returns null when a placeholder is present but the matching secret is missing. */
    public fun expand(username: Secret<String>?, password: Secret<String>?): SensitiveUrl? {
        var result = template
        if (USERNAME in result) result = result.replace(USERNAME, PercentEncoding.encode(username?.unsafeValue() ?: return null))
        if (PASSWORD in result) result = result.replace(PASSWORD, PercentEncoding.encode(password?.unsafeValue() ?: return null))
        return SensitiveUrl.of(result)
    }

    /** Templates can still contain opaque provider tokens, so printing is structurally redacted. */
    override fun toString(): String = Redactor.STRUCTURAL.redactUrl(template)

    override fun equals(other: Any?): Boolean = other is UrlTemplate && other.template == template

    override fun hashCode(): Int = template.hashCode()

    public companion object {
        public const val USERNAME: String = "{credential:username}"
        public const val PASSWORD: String = "{credential:password}"

        /** Returns null for URLs that cannot be parsed; callers must reject those rather than store them. */
        public fun fromUrl(url: String, username: String?, password: String?): UrlTemplate? {
            val parsed = ParsedUrl.parse(url) ?: return null
            if (!parsed.hasAuthority) return UrlTemplate(url)

            fun component(raw: String, plusAsSpace: Boolean = false): String {
                val decoded = PercentEncoding.decode(raw, plusAsSpace)
                return when {
                    password != null && decoded == password -> PASSWORD
                    username != null && decoded == username -> USERNAME
                    else -> raw
                }
            }

            val userInfo = parsed.userInfo?.let { info ->
                val colon = info.indexOf(':')
                if (colon < 0) component(info) else component(info.substring(0, colon)) + ":" + component(info.substring(colon + 1))
            }
            val path = parsed.path.split('/').joinToString("/") { if (it.isEmpty()) it else component(it) }
            val query = parsed.query?.split('&')?.joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) pair else pair.substring(0, eq + 1) + component(pair.substring(eq + 1), plusAsSpace = true)
            }
            val rebuilt = buildString {
                append(parsed.scheme).append("://")
                if (userInfo != null) append(userInfo).append('@')
                append(parsed.hostAndPort).append(path)
                if (query != null) append('?').append(query)
                if (parsed.fragment != null) append('#').append(parsed.fragment)
            }
            return UrlTemplate(rebuilt)
        }
    }
}
