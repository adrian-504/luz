package app.iptvplayer.protocols.media

import app.iptvplayer.domain.capability.Capabilities
import app.iptvplayer.domain.capability.PlatformCapabilities
import app.iptvplayer.domain.capability.StreamFormat
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.model.AccountStatus
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.PreferredStreamFormat
import app.iptvplayer.domain.model.ProtocolId
import app.iptvplayer.domain.model.Provider
import app.iptvplayer.domain.model.ProviderAccount
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.protocols.xtream.XtreamEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class MediaSourceResolverTest {
    private val user = "canary-user"
    private val pass = "CANARY-PW-7f3a9c-DO-NOT-LOG"
    private val secrets = SecretBundle(username = Secret(user), password = Secret(pass))
    private val android = PlatformCapabilities(
        mapOf(
            StreamProtocol.PROGRESSIVE_TS to Support.SUPPORTED,
            StreamProtocol.HLS to Support.SUPPORTED,
        ),
        emptyMap(),
        Support.SUPPORTED,
        Support.SUPPORTED,
    )
    private val apple = PlatformCapabilities(
        mapOf(
            StreamProtocol.PROGRESSIVE_TS to Support.UNSUPPORTED,
            StreamProtocol.HLS to Support.SUPPORTED,
        ),
        emptyMap(),
        Support.SUPPORTED,
        Support.SUPPORTED,
    )

    private fun provider(formats: Set<StreamFormat>) = Provider(
        id = ProviderId("p"), displayName = "Example", protocol = ProtocolId.XTREAM, endpointDisplay = "http://provider.example.com:8080",
        endpoint = UrlTemplate(
            "http://provider.example.com:8080",
        ),
        credentialRef = CredentialRef("c"), capabilities = Capabilities.UNDISCOVERED,
        account = ProviderAccount(AccountStatus.ACTIVE, null, null, 1, 0, formats, null), transportSecurity = TransportSecurity.CLEARTEXT,
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0),
    )

    private fun source(locator: MediaLocator, headers: MediaHeaders = MediaHeaders(), hint: StreamProtocol = StreamProtocol.UNKNOWN) =
        MediaSource(MediaSourceId("ms"), ContentRef(ContentType.CHANNEL, "ch"), locator, hint, headers, null, null, 0)

    private fun resolve(
        source: MediaSource,
        formats: Set<StreamFormat> = setOf(StreamFormat.HLS, StreamFormat.MPEG_TS),
        platform: PlatformCapabilities = android,
        preferred: PreferredStreamFormat = PreferredStreamFormat.AUTO,
        bundle: SecretBundle? = secrets,
        headerSecrets: Map<CredentialRef, Secret<String>> = emptyMap(),
    ) = MediaSourceResolver.resolve(source, provider(formats), bundle, headerSecrets, platform, preferred)

    @Test
    fun liveOutputPrefersTsWhereSupportedAndHlsOnApple() {
        val live = source(MediaLocator.XtreamStream(XtreamStreamKind.LIVE, "1001", null))
        val androidUrl = assertIs<ResolveResult.Resolved>(resolve(live)).source
        assertEquals("http://provider.example.com:8080/live/$user/$pass/1001.ts", androidUrl.url.unsafeRawValue())
        assertEquals(StreamProtocol.PROGRESSIVE_TS, androidUrl.protocol)
        val appleUrl = assertIs<ResolveResult.Resolved>(resolve(live, platform = apple)).source
        assertEquals("http://provider.example.com:8080/live/$user/$pass/1001.m3u8", appleUrl.url.unsafeRawValue())
        assertEquals(StreamProtocol.HLS, appleUrl.protocol)
        assertEquals(
            "m3u8" to StreamProtocol.HLS,
            MediaSourceResolver.liveOutput(setOf(StreamFormat.HLS), android, PreferredStreamFormat.AUTO),
        )
        assertEquals(
            "ts" to StreamProtocol.PROGRESSIVE_TS,
            MediaSourceResolver.liveOutput(setOf(StreamFormat.MPEG_TS), apple, PreferredStreamFormat.AUTO),
            "TS-only account on Apple: attempt TS; playability reports it",
        )
        assertEquals("m3u8" to StreamProtocol.HLS, MediaSourceResolver.liveOutput(emptySet(), android, PreferredStreamFormat.HLS))
        assertEquals(
            "ts" to StreamProtocol.PROGRESSIVE_TS,
            MediaSourceResolver.liveOutput(setOf(StreamFormat.MPEG_TS), android, PreferredStreamFormat.HLS),
        )
    }

    @Test
    fun vodAndDirectUrls() {
        val movie = assertIs<ResolveResult.Resolved>(
            resolve(source(MediaLocator.XtreamStream(XtreamStreamKind.MOVIE, "2001", "mkv"), hint = StreamProtocol.MATROSKA)),
        ).source
        assertEquals("http://provider.example.com:8080/movie/$user/$pass/2001.mkv", movie.url.unsafeRawValue())
        assertEquals(StreamProtocol.MATROSKA, movie.protocol)

        val template = UrlTemplate("https://cdn.example.net/get?u=${UrlTemplate.USERNAME}&p=${UrlTemplate.PASSWORD}")
        val cookieRef = CredentialRef("fp_cookie")
        val headers =
            MediaHeaders(
                userAgent = "ExampleUA/1.0",
                referrer = "https://www.example.com/",
                custom = mapOf("Origin" to "https://www.example.com"),
                sensitive = mapOf("Cookie" to cookieRef),
            )
        val direct = assertIs<ResolveResult.Resolved>(
            resolve(
                source(MediaLocator.DirectUrl(template), headers, StreamProtocol.HLS),
                headerSecrets = mapOf(cookieRef to Secret("session=canary-cookie")),
            ),
        ).source
        assertEquals("https://cdn.example.net/get?u=$user&p=$pass", direct.url.unsafeRawValue())
        assertEquals(
            mapOf("User-Agent" to "ExampleUA/1.0", "Referer" to "https://www.example.com/", "Origin" to "https://www.example.com"),
            direct.headers,
        )
        assertEquals("session=canary-cookie", direct.sensitiveHeaders["Cookie"]?.unsafeValue())
        assertFalse(direct.toString().contains(pass) || direct.toString().contains("canary-cookie"), direct.toString())
    }

    @Test
    fun failuresAreExplicit() {
        val live = source(MediaLocator.XtreamStream(XtreamStreamKind.LIVE, "1", null))
        assertEquals(ResolveResult.Failed(ResolveFailure.MISSING_CREDENTIALS), resolve(live, bundle = null))
        assertEquals(ResolveResult.Failed(ResolveFailure.UNSUPPORTED_LOCATOR), resolve(source(MediaLocator.XtreamTimeshift("1"))))
        val withSecretHeader =
            source(
                MediaLocator.DirectUrl(UrlTemplate("https://cdn.example.net/a.m3u8")),
                MediaHeaders(sensitive = mapOf("Cookie" to CredentialRef("missing"))),
            )
        assertEquals(ResolveResult.Failed(ResolveFailure.MISSING_HEADER_SECRET), resolve(withSecretHeader))
    }

    @Test
    fun endpointNormalization() {
        assertEquals("http://provider.example.com:8080", XtreamEndpoint.parse(" http://provider.example.com:8080/player_api.php ")?.base)
        assertEquals("https://provider.example.com/panel", XtreamEndpoint.parse("https://provider.example.com/panel/get.php")?.base)
        assertEquals("http://provider.example.com", XtreamEndpoint.parse("http://PROVIDER.example.com///")?.base)
        assertNull(XtreamEndpoint.parse("provider.example.com"))
        assertNull(XtreamEndpoint.parse("ftp://provider.example.com"))
        assertNull(XtreamEndpoint.parse("http://canary-user:CANARY-PW-7f3a9c-DO-NOT-LOG@provider.example.com"))
        val endpoint = assertNotNull(XtreamEndpoint.parse("http://provider.example.com"))
        val special = app.iptvplayer.protocols.xtream.XtreamCredentials(Secret("canary user"), Secret("CANARY&p/w?"))
        assertEquals(
            "http://provider.example.com/player_api.php?username=canary%20user&password=CANARY%26p%2Fw%3F&action=get_series_info&series_id=7",
            endpoint.apiUrl(special, "get_series_info", listOf("series_id" to "7")).unsafeRawValue(),
        )
        assertEquals(
            "http://provider.example.com/series/canary%20user/CANARY%26p%2Fw%3F/30001.mkv",
            endpoint.streamUrl(special, XtreamStreamKind.SERIES, "30001", "mkv").unsafeRawValue(),
        )
    }
}
