package app.iptvplayer.protocols.xtream

import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.net.ParsedUrl
import app.iptvplayer.domain.security.PercentEncoding
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.domain.security.UrlTemplate

/** Xtream credentials held in memory for the duration of a request or import (ADR-0015). */
public class XtreamCredentials(public val username: Secret<String>, public val password: Secret<String>) {
    override fun toString(): String = "XtreamCredentials(‹redacted›)"
}

/**
 * The user-entered panel base URL, normalized (docs/IPTV_PROTOCOLS.md §4.1). Every URL containing credentials is built
 * here, only in memory, as a [SensitiveUrl].
 */
public class XtreamEndpoint private constructor(
    /** `scheme://host[:port][/path]` without trailing slash, userinfo, query or credentials. */
    public val base: String,
    public val scheme: String,
    public val host: String,
) {
    /** Non-secret identity for UI (docs/DOMAIN_MODEL.md Provider.endpointDisplay). */
    public val display: String get() = base

    /** Safe to persist as `Provider.endpoint`. */
    public val template: UrlTemplate get() = UrlTemplate(base)

    public fun apiUrl(
        credentials: XtreamCredentials,
        action: String? = null,
        parameters: List<Pair<String, String>> = emptyList(),
    ): SensitiveUrl {
        val query = buildList {
            add("username" to credentials.username.unsafeValue())
            add("password" to credentials.password.unsafeValue())
            if (action != null) add("action" to action)
            addAll(parameters)
        }.joinToString("&") { (k, v) -> "${PercentEncoding.encode(k)}=${PercentEncoding.encode(v)}" }
        return SensitiveUrl.of("$base/player_api.php?$query")
    }

    /** `xmltv.php` with credential placeholders: safe to persist as an EPG source template. */
    public val xmltvTemplate: UrlTemplate
        get() = UrlTemplate("$base/xmltv.php?username=${UrlTemplate.USERNAME}&password=${UrlTemplate.PASSWORD}")

    public fun streamUrl(credentials: XtreamCredentials, kind: XtreamStreamKind, streamId: String, extension: String): SensitiveUrl {
        val path = when (kind) {
            XtreamStreamKind.LIVE -> "live"
            XtreamStreamKind.MOVIE -> "movie"
            XtreamStreamKind.SERIES -> "series"
        }
        val user = PercentEncoding.encode(credentials.username.unsafeValue())
        val pass = PercentEncoding.encode(credentials.password.unsafeValue())
        return SensitiveUrl.of("$base/$path/$user/$pass/${PercentEncoding.encode(streamId)}.${PercentEncoding.encode(extension)}")
    }

    override fun toString(): String = "XtreamEndpoint($base)"

    override fun equals(other: Any?): Boolean = other is XtreamEndpoint && other.base == base

    override fun hashCode(): Int = base.hashCode()

    public companion object {
        private val TRAILING_FILES = listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")

        /**
         * Accepts `http(s)://host[:port][/path]`, tolerating a pasted `player_api.php`/`get.php` path and trailing
         * slashes. Returns null for other schemes, a missing host, or embedded credentials (they belong in the
         * credential fields, not the URL).
         */
        public fun parse(input: String): XtreamEndpoint? {
            val parsed = ParsedUrl.parse(input.trim()) ?: return null
            if (parsed.scheme != "http" && parsed.scheme != "https") return null
            if (!parsed.hasAuthority || parsed.host.isEmpty() || parsed.userInfo != null) return null
            var path = parsed.path.trimEnd('/')
            TRAILING_FILES.firstOrNull { path.endsWith(it, ignoreCase = true) }?.let { path = path.dropLast(it.length).trimEnd('/') }
            return XtreamEndpoint("${parsed.scheme}://${parsed.hostAndPort}$path", parsed.scheme, parsed.host)
        }
    }
}
