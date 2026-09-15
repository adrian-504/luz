package app.iptvplayer.domain.security

import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.ports.TransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Canary credentials must never survive redaction in any documented form (docs/SECURITY.md §4, §8). */
class CredentialSafetyTest {
    private val user = "canary-user"
    private val pass = "CANARY-PW-7f3a9c-DO-NOT-LOG"
    private val trickyPass = "CANARY p@ss/wörd&=+"
    private val redactor = Redactor(knownSecrets = listOf(user, pass, trickyPass))

    private fun assertNoCanary(text: String) {
        val forms = listOf(user, pass, trickyPass).flatMap {
            listOf(it, PercentEncoding.encode(it), PercentEncoding.encode(it, spaceAsPlus = true))
        }
        for (form in forms) assertFalse(text.contains(form), "leaked '$form' in: $text")
        assertFalse(text.contains("CANARY", ignoreCase = true) && !text.contains(Redactor.MARK), "canary marker in: $text")
    }

    @Test
    fun xtreamStreamPathIsMaskedStructurally() {
        val url = "http://provider.example.com:8080/live/$user/$pass/1001.ts"
        assertEquals("http://provider.example.com:8080/live/{u}/{p}/1001.ts", Redactor.STRUCTURAL.redactUrl(url))
        assertEquals(
            "http://provider.example.com/timeshift/{u}/{p}/120/2026-09-14:12-00/1001.ts",
            Redactor.STRUCTURAL.redactUrl("http://provider.example.com/timeshift/$user/$pass/120/2026-09-14:12-00/1001.ts"),
        )
    }

    @Test
    fun shortPathsThatOnlyLookLikeXtreamAreUntouched() {
        assertEquals(
            "https://cdn.example.net/live/news/index.m3u8",
            Redactor.STRUCTURAL.redactUrl("https://cdn.example.net/live/news/index.m3u8"),
        )
    }

    @Test
    fun sensitiveQueryParametersAndUserInfoAreRemoved() {
        val url = "http://$user:$pass@provider.example.com/get.php?username=$user&password=$pass&type=m3u_plus&output=ts"
        val redacted = Redactor.STRUCTURAL.redactUrl(url)
        assertEquals(
            "http://provider.example.com/get.php?username=${Redactor.MARK}&password=${Redactor.MARK}&type=m3u_plus&output=ts",
            redacted,
        )
        assertNoCanary(redacted)
    }

    @Test
    fun knownSecretsAreRemovedRawAndPercentEncoded() {
        val encoded = PercentEncoding.encode(trickyPass)
        val text = "failed for $trickyPass and $encoded and ${encoded.lowercase()} and ${PercentEncoding.encode(
            trickyPass,
            spaceAsPlus = true,
        )}"
        assertNoCanary(redactor.redactText(text))
    }

    @Test
    fun freeTextWithUrlsAndEchoedJsonIsRedacted() {
        val text =
            "GET http://provider.example.com/player_api.php?username=$user&password=$pass failed; " +
                """body={"user_info":{"username":"$user","password":"$pass","auth":1}} """ +
                """next="https://cdn.example.net/movie/$user/$pass/2001.mkv""""
        val structural = Redactor.STRUCTURAL.redactText(text)
        assertNoCanary(structural)
        assertTrue(structural.contains("https://cdn.example.net/movie/{u}/{p}/2001.mkv"), structural)
        assertTrue(structural.contains("\"auth\":1"), structural)
    }

    @Test
    fun keyValueRuleDoesNotHitUnrelatedWords() {
        assertEquals("bypass: none, passport=ok", Redactor.STRUCTURAL.redactText("bypass: none, passport=ok"))
    }

    @Test
    fun sensitiveHeadersAreMasked() {
        assertEquals(Redactor.MARK, redactor.redactHeader("Cookie", "session=abc"))
        assertEquals(Redactor.MARK, redactor.redactHeader("authorization", "Bearer xyz"))
        assertEquals("ExamplePlayer/1.0", redactor.redactHeader("User-Agent", "ExamplePlayer/1.0"))
    }

    @Test
    fun hostsCanBePseudonymizedPerExport() {
        val export = Redactor(pseudonymizeHosts = true)
        assertEquals("http://host-1/live/{u}/{p}/1.ts", export.redactUrl("http://provider.example.com/live/$user/$pass/1.ts"))
        assertEquals("https://host-2/a", export.redactUrl("https://cdn.example.net/a"))
        assertEquals("http://host-1/b", export.redactUrl("http://provider.example.com/b"))
    }

    @Test
    fun sensitiveTypesNeverPrintSecrets() {
        val url = SensitiveUrl.of("http://provider.example.com/live/$user/$pass/1001.ts")
        val secret = Secret(pass)
        val bundle = SecretBundle(username = Secret(user), password = secret, secretUrl = url)
        val template = UrlTemplate("https://cdn.example.net/x?token=$pass")
        val printed = listOf(url.toString(), "$secret", bundle.toString(), template.toString(), "${listOf(url, secret)}")
        printed.forEach(::assertNoCanary)
        assertEquals("http://provider.example.com/live/$user/$pass/1001.ts", url.unsafeRawValue())
        assertEquals(pass, secret.unsafeValue())
        assertEquals(listOf(user, pass, url.unsafeRawValue()), bundle.rawValues())
    }

    @Test
    fun sensitiveUrlPrintsOriginOnlyEvenForUnknownParameterNames() {
        val url = SensitiveUrl.of("https://cdn.example.net:8443/token/abc123/get?u=$user&p=$pass#frag")
        assertEquals("https://cdn.example.net:8443/${Redactor.MARK}", url.toString())
        assertEquals("https://cdn.example.net", SensitiveUrl.of("https://cdn.example.net").toString())
        assertEquals(Redactor.MARK, SensitiveUrl.of("not a url $pass").toString())
    }

    @Test
    fun transportExceptionMessageHasNoUrl() {
        assertEquals("Transport failure: DNS", TransportException(NetworkErrorKind.DNS).message)
    }

    @Test
    fun templatesStoreNoCredentialValues() {
        val xtream = assertNotNull(UrlTemplate.fromUrl("http://provider.example.com/live/$user/$pass/1001.ts", user, pass))
        assertEquals("http://provider.example.com/live/${UrlTemplate.USERNAME}/${UrlTemplate.PASSWORD}/1001.ts", xtream.template)

        val m3u =
            assertNotNull(
                UrlTemplate.fromUrl(
                    "https://provider.example.com/get.php?username=$user&password=${PercentEncoding.encode(
                        trickyPass,
                        spaceAsPlus = true,
                    )}&type=m3u_plus",
                    user,
                    trickyPass,
                ),
            )
        assertEquals(
            "https://provider.example.com/get.php?username=${UrlTemplate.USERNAME}&password=${UrlTemplate.PASSWORD}&type=m3u_plus",
            m3u.template,
        )

        val userInfo = assertNotNull(UrlTemplate.fromUrl("http://$user:$pass@provider.example.com:8000/stream", user, pass))
        assertEquals("http://${UrlTemplate.USERNAME}:${UrlTemplate.PASSWORD}@provider.example.com:8000/stream", userInfo.template)
        listOf(xtream, m3u, userInfo).forEach {
            assertTrue(it.hasCredentialPlaceholders)
            assertNoCanary(it.template)
        }
    }

    @Test
    fun templatingReplacesWholeComponentsOnly() {
        val template = assertNotNull(UrlTemplate.fromUrl("http://provider.example.com/live/live/$pass/live.ts?live=1", "live", pass))
        assertEquals(
            "http://provider.example.com/${UrlTemplate.USERNAME}/${UrlTemplate.USERNAME}/${UrlTemplate.PASSWORD}/live.ts?live=${""}1",
            template.template,
        )
    }

    @Test
    fun expandRoundTripsAndEncodesSpecialCharacters() {
        val template = assertNotNull(UrlTemplate.fromUrl("http://provider.example.com/live/$user/$pass/1001.ts", user, pass))
        assertEquals("http://provider.example.com/live/$user/$pass/1001.ts", template.expand(Secret(user), Secret(pass))?.unsafeRawValue())

        val tricky = UrlTemplate("http://provider.example.com/live/${UrlTemplate.USERNAME}/${UrlTemplate.PASSWORD}/1.ts")
        val expanded = assertNotNull(tricky.expand(Secret(user), Secret(trickyPass))).unsafeRawValue()
        assertEquals("http://provider.example.com/live/$user/CANARY%20p%40ss%2Fw%C3%B6rd%26%3D%2B/1.ts", expanded)
        assertNull(tricky.expand(Secret(user), null))
    }

    @Test
    fun unparseableUrlsAreNotTemplated() {
        assertNull(UrlTemplate.fromUrl("not a url", user, pass))
    }
}
