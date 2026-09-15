package app.iptvplayer.domain.net

/** Where a URL will be used; decides the scheme allowlist (docs/SECURITY.md §5, ADR-0016). */
public enum class UrlContext { SOURCE, API, EPG, ARTWORK, STREAM }

public enum class UrlFlag {
    /** Not TLS-protected (http, rtmp, rtsp, udp, rtp). Must be labelled in UI. */
    CLEARTEXT,
    LOCAL_NETWORK,
    IP_LITERAL,

    /** Host contains non-ASCII characters; display as punycode to prevent spoofing. */
    NON_ASCII_HOST,
    HAS_USERINFO,

    /** Stream scheme other than http(s); playability depends on platform capabilities. */
    NON_HTTP_STREAM,
}

public enum class UrlRejection { MALFORMED, TOO_LONG, CONTROL_CHARACTERS, SCHEME_NOT_ALLOWED, MISSING_HOST }

public sealed interface UrlCheck {
    public data class Allowed(public val url: ParsedUrl, public val flags: Set<UrlFlag>) : UrlCheck

    public data class Rejected(public val reason: UrlRejection) : UrlCheck
}

public enum class RedirectRejection { TOO_MANY_REDIRECTS, HTTPS_DOWNGRADE, INVALID_TARGET }

public sealed interface RedirectCheck {
    public data class Allowed(public val url: ParsedUrl, public val flags: Set<UrlFlag>) : RedirectCheck

    public data class Rejected(public val reason: RedirectRejection, public val targetRejection: UrlRejection? = null) : RedirectCheck
}

public object UrlPolicy {
    public const val MAX_URL_LENGTH: Int = 8 * 1024
    public const val MAX_REDIRECTS: Int = 5

    private val HTTP_SCHEMES = setOf("http", "https")
    private val EXTRA_STREAM_SCHEMES = setOf("rtmp", "rtmps", "rtsp", "rtsps", "udp", "rtp")
    private val CLEARTEXT_SCHEMES = setOf("http", "rtmp", "rtsp", "udp", "rtp")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")

    public fun allowedSchemes(context: UrlContext): Set<String> =
        if (context == UrlContext.STREAM) HTTP_SCHEMES + EXTRA_STREAM_SCHEMES else HTTP_SCHEMES

    public fun check(raw: String, context: UrlContext): UrlCheck {
        if (raw.length > MAX_URL_LENGTH) return UrlCheck.Rejected(UrlRejection.TOO_LONG)
        if (raw.any { it.code <= 0x20 || it.code == 0x7F }) return UrlCheck.Rejected(UrlRejection.CONTROL_CHARACTERS)
        val url = ParsedUrl.parse(raw) ?: return UrlCheck.Rejected(UrlRejection.MALFORMED)
        if (url.scheme !in allowedSchemes(context)) return UrlCheck.Rejected(UrlRejection.SCHEME_NOT_ALLOWED)
        if (!url.hasAuthority || url.host.isEmpty()) return UrlCheck.Rejected(UrlRejection.MISSING_HOST)

        val flags = LinkedHashSet<UrlFlag>()
        if (url.scheme in CLEARTEXT_SCHEMES) flags += UrlFlag.CLEARTEXT
        if (url.scheme !in HTTP_SCHEMES) flags += UrlFlag.NON_HTTP_STREAM
        if (url.userInfo != null) flags += UrlFlag.HAS_USERINFO
        if (url.host.any { it.code > 0x7F }) flags += UrlFlag.NON_ASCII_HOST
        val ipv4 = parseIpv4(url.host)
        if (url.isIpv6Literal || ipv4 != null) flags += UrlFlag.IP_LITERAL
        if (isLocalNetwork(url.host, url.isIpv6Literal, ipv4)) flags += UrlFlag.LOCAL_NETWORK
        return UrlCheck.Allowed(url, flags)
    }

    /** Transports never follow redirects themselves; every hop is checked here. */
    public fun checkRedirect(from: ParsedUrl, location: String, redirectsSoFar: Int, context: UrlContext): RedirectCheck {
        if (redirectsSoFar >= MAX_REDIRECTS) return RedirectCheck.Rejected(RedirectRejection.TOO_MANY_REDIRECTS)
        val target = resolve(from, location)
            ?: return RedirectCheck.Rejected(RedirectRejection.INVALID_TARGET, UrlRejection.MALFORMED)
        return when (val checked = check(target, context)) {
            is UrlCheck.Rejected -> RedirectCheck.Rejected(RedirectRejection.INVALID_TARGET, checked.reason)
            is UrlCheck.Allowed ->
                if (from.scheme == "https" && checked.url.scheme != "https") {
                    RedirectCheck.Rejected(RedirectRejection.HTTPS_DOWNGRADE)
                } else {
                    RedirectCheck.Allowed(checked.url, checked.flags)
                }
        }
    }

    internal fun resolve(base: ParsedUrl, location: String): String? {
        if (location.isEmpty()) return null
        if (ParsedUrl.parse(location)?.hasAuthority == true) return location
        val origin = "${base.scheme}://${base.hostAndPort}"
        return when {
            location.startsWith("//") -> "${base.scheme}:$location"
            location.startsWith("/") -> origin + location
            location.startsWith("?") -> origin + base.path + location
            else -> origin + base.path.substringBeforeLast('/', "") + "/" + location
        }
    }

    private fun parseIpv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { part ->
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            part.toInt().takeIf { it <= 255 } ?: return null
        }
    }

    private fun isLocalNetwork(host: String, ipv6: Boolean, ipv4: List<Int>?): Boolean {
        if (ipv4 != null) {
            val (a, b) = ipv4
            return a == 10 || a == 127 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        }
        if (ipv6) {
            val h = host.lowercase()
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
                h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
        }
        return host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }
    }
}
