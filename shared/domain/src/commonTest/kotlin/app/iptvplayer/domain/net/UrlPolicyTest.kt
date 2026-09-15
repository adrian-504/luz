package app.iptvplayer.domain.net

import app.iptvplayer.domain.security.PercentEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrlPolicyTest {
    private fun allowed(raw: String, context: UrlContext = UrlContext.SOURCE): UrlCheck.Allowed =
        assertIs<UrlCheck.Allowed>(UrlPolicy.check(raw, context), raw)

    private fun rejected(raw: String, context: UrlContext = UrlContext.SOURCE): UrlRejection =
        assertIs<UrlCheck.Rejected>(UrlPolicy.check(raw, context), raw).reason

    @Test
    fun parsesComponents() {
        val url =
            assertNotNull(
                ParsedUrl.parse("HTTPS://canary-user:CANARY-PW-7f3a9c-DO-NOT-LOG@CDN.Example.NET:8443/a/b.m3u8?x=1&y=2#frag?not-query"),
            )
        assertEquals("https", url.scheme)
        assertEquals("canary-user:CANARY-PW-7f3a9c-DO-NOT-LOG", url.userInfo)
        assertEquals("cdn.example.net", url.host)
        assertEquals(8443, url.port)
        assertEquals("/a/b.m3u8", url.path)
        assertEquals("x=1&y=2", url.query)
        assertEquals("frag?not-query", url.fragment)
        assertEquals("https://cdn.example.net:8443/a/b.m3u8?x=1&y=2#frag?not-query", url.toUrlString(includeUserInfo = false))
        assertEquals("ParsedUrl(scheme=https)", url.toString())
    }

    @Test
    fun parsesIpv6AndRejectsMalformedAuthorities() {
        val v6 = assertNotNull(ParsedUrl.parse("http://[fd00::1]:8080/x"))
        assertEquals("fd00::1", v6.host)
        assertEquals("[fd00::1]:8080", v6.hostAndPort)
        assertNull(ParsedUrl.parse("http://[fd00::1/x"))
        assertNull(ParsedUrl.parse("http://host:99999/"))
        assertNull(ParsedUrl.parse("http://host:12ab/"))
        assertNull(ParsedUrl.parse("http://::1/"))
        assertNull(ParsedUrl.parse("1http://host/"))
        assertNull(ParsedUrl.parse("no-scheme"))
    }

    @Test
    fun httpsAndHttpAllowedForSourcesWithCleartextFlag() {
        assertTrue(UrlFlag.CLEARTEXT !in allowed("https://provider.example.com/list.m3u").flags)
        assertTrue(UrlFlag.CLEARTEXT in allowed("http://provider.example.com/list.m3u").flags)
    }

    @Test
    fun dangerousSchemesAreRejectedEverywhere() {
        for (context in UrlContext.entries) {
            for (raw in listOf("javascript:alert(1)", "file:///etc/passwd", "content://x/y", "data:text/plain,hi", "ftp://example.com/a")) {
                assertEquals(UrlRejection.SCHEME_NOT_ALLOWED, rejected(raw, context), "$raw in $context")
            }
        }
    }

    @Test
    fun streamSchemesOnlyInStreamContext() {
        assertEquals(UrlRejection.SCHEME_NOT_ALLOWED, rejected("rtmp://live.example.com/app/stream", UrlContext.API))
        val flags = allowed("rtmp://live.example.com/app/stream", UrlContext.STREAM).flags
        assertTrue(UrlFlag.NON_HTTP_STREAM in flags && UrlFlag.CLEARTEXT in flags)
        assertTrue(UrlFlag.CLEARTEXT !in allowed("rtsps://live.example.com/s", UrlContext.STREAM).flags)
    }

    @Test
    fun structuralRejections() {
        assertEquals(UrlRejection.CONTROL_CHARACTERS, rejected("http://example.com/a b"))
        assertEquals(UrlRejection.CONTROL_CHARACTERS, rejected("http://example.com/\u0000"))
        assertEquals(UrlRejection.MISSING_HOST, rejected("http:///path"))
        assertEquals(UrlRejection.MALFORMED, rejected("http://[::1/"))
        assertEquals(UrlRejection.TOO_LONG, rejected("https://example.com/" + "a".repeat(UrlPolicy.MAX_URL_LENGTH)))
    }

    @Test
    fun flagsLocalNetworkIpLiteralsUserInfoAndNonAsciiHosts() {
        for (raw in listOf(
            "http://192.168.1.20:8080/",
            "http://10.0.0.5/",
            "http://172.20.1.1/",
            "http://127.0.0.1/",
            "http://localhost:34400/",
            "http://nas.local/",
            "http://[fe80::1]/",
            "http://[fd12::2]/",
        )) {
            assertTrue(UrlFlag.LOCAL_NETWORK in allowed(raw).flags, raw)
        }
        for (raw in listOf("http://192.0.2.10/", "http://172.32.0.1/", "https://provider.example.com/")) {
            assertTrue(UrlFlag.LOCAL_NETWORK !in allowed(raw).flags, raw)
        }
        assertTrue(UrlFlag.IP_LITERAL in allowed("http://192.0.2.10/").flags)
        assertTrue(UrlFlag.IP_LITERAL !in allowed("http://999.1.1.1/").flags)
        assertTrue(UrlFlag.HAS_USERINFO in allowed("http://canary-user:CANARY-PW-7f3a9c-DO-NOT-LOG@provider.example.com/").flags)
        assertTrue(UrlFlag.NON_ASCII_HOST in allowed("https://exämple.com/").flags)
    }

    @Test
    fun redirectsRejectDowngradeAndLoops() {
        val https = assertNotNull(ParsedUrl.parse("https://provider.example.com/a/b/list.m3u"))
        assertEquals(
            RedirectRejection.HTTPS_DOWNGRADE,
            assertIs<RedirectCheck.Rejected>(UrlPolicy.checkRedirect(https, "http://cdn.example.net/x", 0, UrlContext.SOURCE)).reason,
        )
        assertEquals(
            RedirectRejection.TOO_MANY_REDIRECTS,
            assertIs<RedirectCheck.Rejected>(
                UrlPolicy.checkRedirect(https, "https://cdn.example.net/x", UrlPolicy.MAX_REDIRECTS, UrlContext.SOURCE),
            ).reason,
        )
        val invalid = assertIs<RedirectCheck.Rejected>(UrlPolicy.checkRedirect(https, "file:///etc/passwd", 0, UrlContext.SOURCE))
        assertEquals(RedirectRejection.INVALID_TARGET, invalid.reason)
        assertEquals(UrlRejection.SCHEME_NOT_ALLOWED, invalid.targetRejection)

        val http = assertNotNull(ParsedUrl.parse("http://provider.example.com/x"))
        assertIs<RedirectCheck.Allowed>(UrlPolicy.checkRedirect(http, "https://secure.example.com/x", 0, UrlContext.SOURCE))
    }

    @Test
    fun relativeRedirectsResolveAgainstTheBase() {
        val base = assertNotNull(ParsedUrl.parse("https://provider.example.com:8443/dir/sub/list.m3u?old=1"))
        assertEquals("https://other.example.com/y", UrlPolicy.resolve(base, "https://other.example.com/y"))
        assertEquals("https://cdn.example.net/z", UrlPolicy.resolve(base, "//cdn.example.net/z"))
        assertEquals("https://provider.example.com:8443/root", UrlPolicy.resolve(base, "/root"))
        assertEquals("https://provider.example.com:8443/dir/sub/next.m3u", UrlPolicy.resolve(base, "next.m3u"))
        assertEquals("https://provider.example.com:8443/dir/sub/list.m3u?page=2", UrlPolicy.resolve(base, "?page=2"))
        assertNull(UrlPolicy.resolve(base, ""))
    }

    @Test
    fun percentEncodingRoundTrips() {
        val value = "a b/c?d&e=f+gö東🎬~_.-"
        assertEquals(value, PercentEncoding.decode(PercentEncoding.encode(value)))
        assertEquals(value, PercentEncoding.decode(PercentEncoding.encode(value, spaceAsPlus = true), plusAsSpace = true))
        assertEquals("100%zz", PercentEncoding.decode("100%zz"))
        assertEquals("🎬%", PercentEncoding.decode("🎬%"))
    }
}
