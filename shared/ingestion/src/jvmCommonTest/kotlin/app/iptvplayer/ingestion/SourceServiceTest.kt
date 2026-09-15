package app.iptvplayer.ingestion

import app.iptvplayer.domain.capability.PlatformCapabilities
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpResponse
import app.iptvplayer.domain.ports.HttpTimings
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.domain.ports.TransportException
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.ingestion.fixtures.Fixtures
import app.iptvplayer.protocols.media.ResolveResult
import app.iptvplayer.storage.BundledSqliteDriver
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.storage.db.IptvDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private class MemorySecretStore : SecretStore {
    val entries = LinkedHashMap<CredentialRef, SecretBundle>()

    override suspend fun put(ref: CredentialRef, bundle: SecretBundle) {
        entries[ref] = bundle
    }

    override suspend fun get(ref: CredentialRef): SecretBundle? = entries[ref]

    override suspend fun delete(ref: CredentialRef) {
        entries.remove(ref)
    }

    override suspend fun exists(ref: CredentialRef): Boolean = ref in entries
}

/** Provider simulation: routes by host and path/action; [down] makes every request fail at the network level. */
private class ProviderSimulation : HttpTransport {
    var liveStreams: ByteArray = Fixtures.liveStreamsJson
    var xmltv: ByteArray = Fixtures.smallValidXmltv
    var panelPlaylist: ByteArray? = null
    var down = false
    val requests = ArrayList<String>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val url = request.url.unsafeRawValue()
        requests += url
        if (down) throw TransportException(NetworkErrorKind.CONNECTION_REFUSED)
        val action = url.substringAfter("action=", "").substringBefore('&')
        val credentialsOk = CANARY_PASSWORD in url
        val (status, body) = when {
            url.startsWith("http://panel.example.com:8080/player_api.php") -> when {
                !credentialsOk -> 200 to Fixtures.authFailureJson
                action.isEmpty() -> 200 to Fixtures.authSuccessJson
                action == "get_live_categories" -> 200 to Fixtures.liveCategoriesJson
                action == "get_live_streams" -> 200 to liveStreams
                action == "get_short_epg" -> 200 to Fixtures.shortEpgJson
                else -> 200 to "[]".encodeToByteArray()
            }
            url.startsWith("http://panel.example.com:8080/xmltv.php") && credentialsOk -> 200 to xmltv
            url.startsWith("http://panel.example.com:8080/get.php") && credentialsOk && panelPlaylist != null -> 200 to panelPlaylist!!
            url.startsWith("http://lists.example.com/get.php") && credentialsOk -> 200 to Fixtures.smallValidM3u
            url.startsWith("https://lists.example.com/playlist.m3u?token=$CANARY_PASSWORD") -> 200 to Fixtures.smallValidM3u
            url.startsWith("https://lists.example.com/not-a-playlist") -> 200 to Fixtures.htmlErrorBody
            url.startsWith("https://epg.example.org/small-valid.xml") -> 200 to Fixtures.smallValidXmltv
            url.startsWith("https://guide.example.org/guide.xml?key=$CANARY_PASSWORD") -> 200 to Fixtures.smallValidXmltv
            else -> 404 to ByteArray(0)
        }
        return HttpResponse(status, emptyMap(), request.url, ByteArraySource(body), HttpTimings(0, 0))
    }

    companion object {
        const val CANARY_USER = "canary-user"
        const val CANARY_PASSWORD = "CANARY-PW-7f3a9c-DO-NOT-LOG"
    }
}

class SourceServiceTest {
    private val file: File = File.createTempFile("ingestion", ".db").also { it.delete() }
    private val driver = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-14T11:30:00Z")

        override fun monotonicNanos(): Long = 0
    }
    private val transport = ProviderSimulation()
    private val secrets = MemorySecretStore()
    private val content = ContentStore(driver, clock)
    private val epg = EpgStore(driver)
    private val android = PlatformCapabilities(
        protocols = mapOf(StreamProtocol.HLS to Support.SUPPORTED, StreamProtocol.PROGRESSIVE_TS to Support.SUPPORTED),
        drmSchemes = emptyMap(),
        multipleAudioTracks = Support.SUPPORTED,
        subtitles = Support.SUPPORTED,
    )
    private var ids = 0
    private val service = SourceService(transport, secrets, content, epg, clock, android) { "id-${++ids}" }

    @AfterTest
    fun cleanUp() {
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    /** Every byte the database wrote to disk must be free of the canary credentials (ADR-0015). */
    private fun assertDatabaseHasNoSecrets() {
        val bytes = listOf("", "-wal").map {
            File(file.path + it)
        }.filter { it.exists() }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(ProviderSimulation.CANARY_PASSWORD in bytes, "password stored in the database")
        assertFalse("canary-user" in bytes, "username stored in the database")
    }

    @Test
    fun xtreamSourceImportsChannelsGuideAndResolvesStreams() = runTest {
        val added = service.addXtream(
            "My TV",
            "http://panel.example.com:8080/",
            ProviderSimulation.CANARY_USER,
            ProviderSimulation.CANARY_PASSWORD,
        )
        assertIs<AddSourceResult.Added>(added)
        assertEquals(ImportStatus.PUBLISHED, added.live.status)
        val playlist = added.playlistId
        assertEquals(3, added.live.itemCount)
        assertEquals(listOf("Example News", "Demo Sports 1", "Example Kids"), content.channels(playlist).map { it.name })
        assertEquals(3, content.groups(playlist).size)

        val guide = service.refreshEpg(playlist)
        assertEquals(ImportStatus.PUBLISHED, guide.status)
        assertTrue(epg.linkCount(playlist.value) >= 1)
        val news = content.channels(playlist).first { it.name == "Example News" }
        val sports = content.channels(playlist).first { it.name == "Demo Sports 1" }
        val guideRows = epg.nowNext(playlist.value, listOf(news.id.value, sports.id.value), clock.now())
        // Fixture: news 11:00–12:00 UTC is on at 11:30; sports (matched by exact name, no tvg-id) starts 17:00 UTC.
        assertEquals(Instant.parse("2026-09-14T11:00:00Z"), assertNotNull(guideRows[news.id.value]?.current).start)
        assertEquals(Instant.parse("2026-09-14T17:00:00Z"), assertNotNull(guideRows[sports.id.value]?.next).start)

        val resolved = service.resolveChannel(playlist, news.id)
        assertIs<ResolveResult.Resolved>(resolved)
        assertEquals(StreamProtocol.PROGRESSIVE_TS, resolved.source.protocol)
        assertTrue(resolved.source.url.unsafeRawValue().startsWith("http://panel.example.com:8080/live/canary-user/"))
        assertDatabaseHasNoSecrets()
    }

    @Test
    fun guideSummaryTellsAnEmptyProviderGuideFromOneOutsideTheWindow() = runTest {
        val added = assertIs<AddSourceResult.Added>(
            service.addXtream(null, "http://panel.example.com:8080/", ProviderSimulation.CANARY_USER, ProviderSimulation.CANARY_PASSWORD),
        )
        val playlist = added.playlistId

        val full = service.refreshEpg(playlist).guide!!
        assertTrue(full.programmesKept > 0 && full.declaredChannels > 0, "$full")
        assertEquals(null, content.unitState(playlist, ImportUnit.EPG)?.errorCode)
        assertEquals(full.programmesKept.toLong(), content.unitState(playlist, ImportUnit.EPG)?.itemCount)

        transport.xmltv = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"panel\"></tv>\n".encodeToByteArray()
        val empty = service.refreshEpg(playlist)
        assertEquals(ImportStatus.PUBLISHED, empty.status)
        assertEquals(GuideSummary(0, 0, 0, 0, 0, empty.guide!!.linkedChannels), empty.guide.copy(playlistHeader = null))
        assertEquals("FETCH_HTTP_404", empty.guide.playlistHeader, "the simulated panel has no get.php here")
        assertEquals(GuideSummary.EMPTY, content.unitState(playlist, ImportUnit.EPG)?.errorCode)
        assertEquals(0L, content.unitState(playlist, ImportUnit.EPG)?.itemCount)

        transport.xmltv = Fixtures.smallValidXmltv
        val later = SourceService(
            transport,
            secrets,
            content,
            epg,
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-12-01T00:00:00Z")

                override fun monotonicNanos(): Long = 0
            },
            android,
        ) { "id-${++ids}" }
        val outside = later.refreshEpg(playlist).guide!!
        assertEquals(0, outside.programmesKept)
        assertTrue(outside.outsideWindow > 0, "$outside")
        assertEquals(GuideSummary.OUTSIDE_WINDOW, content.unitState(playlist, ImportUnit.EPG)?.errorCode)
    }

    @Test
    fun xtreamPlaylistLinksCanBeAddedWithTheXtreamApi() = runTest {
        val link = "http://panel.example.com:8080/get.php?username=canary-user" +
            "&password=${ProviderSimulation.CANARY_PASSWORD}&type=m3u_plus&output=ts"
        assertTrue(service.isXtreamPlaylistLink(link))
        assertFalse(service.isXtreamPlaylistLink("https://lists.example.com/playlist.m3u?token=canary-token"))

        val added = assertIs<AddSourceResult.Added>(service.addXtreamFromPlaylistLink("Link", link))
        assertEquals(PlaylistType.XTREAM, content.source(added.playlistId)?.type)
        assertEquals(3, added.live.itemCount, "live streams from the API, not the playlist's movies")
        assertTrue(transport.requests.none { "get.php" in it }, "the playlist itself is not downloaded")
        assertDatabaseHasNoSecrets()
    }

    @Test
    fun shortGuideFillsInForAnEmptyXmltvGuideAndIsCached() = runTest {
        val added = assertIs<AddSourceResult.Added>(
            service.addXtream(null, "http://panel.example.com:8080/", ProviderSimulation.CANARY_USER, ProviderSimulation.CANARY_PASSWORD),
        )
        val channels = content.channels(added.playlistId)
        val first = service.shortGuide(added.playlistId, channels.map { it.id })
        assertEquals(channels.map { it.id.value }.toSet(), first.keys)
        val news = assertNotNull(first[channels.first().id.value])
        // Fixture: three programmes 05:00–12:00 UTC; the base64 titles are decoded, invalid base64 is kept as received.
        assertEquals(listOf("Example Morning News", "Example Midday Bulletin", "%%not-base64%%"), news.map { it.title })
        assertEquals(Instant.parse("2026-09-14T12:00:00Z"), news.last().end)

        val requests = transport.requests.count { "get_short_epg" in it }
        assertEquals(channels.size, requests)
        service.shortGuide(added.playlistId, channels.map { it.id })
        assertEquals(requests, transport.requests.count { "get_short_epg" in it }, "cached until the last programme ends")
        assertDatabaseHasNoSecrets()
    }

    @Test
    fun aGuideLinkReplacesAnEmptyProviderGuideAndCanBeCleared() = runTest {
        val added = assertIs<AddSourceResult.Added>(
            service.addXtream(null, "http://panel.example.com:8080/", ProviderSimulation.CANARY_USER, ProviderSimulation.CANARY_PASSWORD),
        )
        val playlist = added.playlistId
        transport.xmltv = "<?xml version=\"1.0\"?><tv></tv>".encodeToByteArray()
        assertEquals(GuideSummary.EMPTY, service.refreshEpg(playlist).guide?.code)

        assertEquals(AddSourceFailure.INVALID_URL, service.setGuideLink(playlist, "not a link"))
        assertEquals(null, service.setGuideLink(playlist, "https://guide.example.org/guide.xml?key=${ProviderSimulation.CANARY_PASSWORD}"))
        assertTrue(service.hasGuideLink(content.source(playlist)!!))
        val custom = service.refreshEpg(playlist)
        assertEquals(ImportStatus.PUBLISHED, custom.status)
        assertTrue(custom.itemCount > 0, "$custom")
        assertTrue(transport.requests.last().startsWith("https://guide.example.org/"))
        assertDatabaseHasNoSecrets()
        val stored = listOf("", "-wal").map { File(file.path + it) }.filter { it.exists() }
            .joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse("guide.example.org" in stored, "the guide link is a secret")

        service.refreshLive(playlist)
        assertTrue(service.hasGuideLink(content.source(playlist)!!), "a channel refresh keeps the guide link")

        service.clearGuideLink(playlist)
        assertFalse(service.hasGuideLink(content.source(playlist)!!))
        val before = transport.requests.size
        service.refreshEpg(playlist)
        assertTrue(
            transport.requests.drop(before).first().startsWith("http://panel.example.com:8080/xmltv.php"),
            "back to the provider's guide",
        )
        service.deleteSource(playlist)
        assertTrue(secrets.entries.isEmpty(), "login and guide link removed with the source")
    }

    @Test
    fun anEmptyXtreamGuideFallsBackToTheGuideInThePlaylistHeader() = runTest {
        val added = assertIs<AddSourceResult.Added>(
            service.addXtream(null, "http://panel.example.com:8080/", ProviderSimulation.CANARY_USER, ProviderSimulation.CANARY_PASSWORD),
        )
        transport.xmltv = "<?xml version=\"1.0\"?><tv></tv>".encodeToByteArray()
        transport.panelPlaylist = (
            "\uFEFF#EXTM3U url-tvg=\"https://guide.example.org/guide.xml?key=${ProviderSimulation.CANARY_PASSWORD}\"\n" +
                "#EXTINF:-1,Example News\nhttp://panel.example.com:8080/live/canary-user/${ProviderSimulation.CANARY_PASSWORD}/1001.ts\n"
            ).encodeToByteArray()

        val outcome = service.refreshEpg(added.playlistId)
        assertEquals(ImportStatus.PUBLISHED, outcome.status)
        assertTrue(outcome.itemCount > 0 && outcome.guide!!.fromPlaylistHeader, "$outcome")
        assertEquals(null, content.unitState(added.playlistId, ImportUnit.EPG)?.errorCode)
        assertFalse(service.hasGuideLink(content.source(added.playlistId)!!), "the advertised link is not saved")
        assertDatabaseHasNoSecrets()

        transport.panelPlaylist = "#EXTM3U\n#EXTINF:-1,Example News\nhttp://x.invalid/1.ts\n".encodeToByteArray()
        assertEquals(GuideSummary.EMPTY, service.refreshEpg(added.playlistId).guide?.code, "no header guide: still empty")
    }

    @Test
    fun playlistHeaderGuideLinksAreParsedConservatively() {
        val provider = app.iptvplayer.domain.security.SensitiveUrl.of(
            "http://p.example.com/xmltv.php?username=canary-user&password=canary-pass",
        )
        fun parse(header: String) = SourceService.advertisedGuide(header, provider)?.unsafeRawValue()
        assertEquals(
            "https://g.example.org/a.xml.gz",
            parse("#EXTM3U url-tvg=\"https://g.example.org/a.xml.gz,https://g.example.org/b.xml\""),
        )
        assertEquals("http://g.example.org/x.xml", parse("\uFEFF#EXTM3U tvg-shift=0 x-tvg-url=\"http://g.example.org/x.xml\""))
        assertEquals(
            null,
            parse("#EXTM3U url-tvg=\"http://p.example.com/xmltv.php?username=canary-user&password=canary-pass\""),
            "same as the empty guide",
        )
        assertEquals(null, parse("#EXTM3U url-tvg=\"ftp://g.example.org/x.xml\""))
        assertEquals(null, parse("<html>url-tvg=\"https://g.example.org/x.xml\""))
        assertEquals(
            "#EXTM3U a",
            SourceService.readFirstLine(app.iptvplayer.domain.ports.ByteArraySource("#EXTM3U a\nrest".encodeToByteArray()), 1024),
        )
    }

    @Test
    fun wrongPasswordIsRejectedAndNothingIsStored() = runTest {
        val result = service.addXtream(null, "http://panel.example.com:8080", ProviderSimulation.CANARY_USER, "wrong")
        assertEquals(AddSourceFailure.INVALID_CREDENTIALS, assertIs<AddSourceResult.Rejected>(result).reason)
        assertTrue(content.sources().isEmpty())
        assertTrue(secrets.entries.isEmpty())
        assertEquals(
            AddSourceFailure.INVALID_URL,
            assertIs<AddSourceResult.Rejected>(service.addXtream(null, "panel.example.com", "u", "p")).reason,
        )
    }

    @Test
    fun m3uSourcesKeepCredentialsAndTokensOutOfTheDatabase() = runTest {
        val xtreamStyle = service.addM3u(
            null,
            "http://lists.example.com/get.php?username=canary-user&password=${ProviderSimulation.CANARY_PASSWORD}&type=m3u_plus",
        )
        val first = assertIs<AddSourceResult.Added>(xtreamStyle)
        assertEquals(4, first.live.itemCount, "the fixture has 4 live entries and 1 movie")

        val tokenUrl = service.addM3u("Token list", "https://lists.example.com/playlist.m3u?token=${ProviderSimulation.CANARY_PASSWORD}")
        val second = assertIs<AddSourceResult.Added>(tokenUrl)
        assertEquals(4, content.channels(second.playlistId).size)
        assertEquals(ImportStatus.PUBLISHED, service.refreshEpg(second.playlistId).status, "guide URL taken from the playlist's url-tvg")
        assertDatabaseHasNoSecrets()

        val html = service.addM3u(null, "https://lists.example.com/not-a-playlist")
        assertEquals(AddSourceFailure.NOT_A_PLAYLIST, assertIs<AddSourceResult.Rejected>(html).reason)
        assertEquals(2, content.sources().size, "a rejected playlist leaves no source behind")
        assertEquals(2, secrets.entries.size)
    }

    @Test
    fun failedRefreshKeepsTheLastChannelsAndDeleteRemovesSecrets() = runTest {
        val added = assertIs<AddSourceResult.Added>(
            service.addXtream(null, "http://panel.example.com:8080", ProviderSimulation.CANARY_USER, ProviderSimulation.CANARY_PASSWORD),
        )
        transport.down = true
        val refresh = service.refreshLive(added.playlistId)
        assertEquals(ImportStatus.FAILED, refresh.status)
        assertEquals(3, content.channels(added.playlistId).size, "channels from the last good import stay")
        assertEquals(ImportStatus.FAILED, content.unitState(added.playlistId, ImportUnit.LIVE)!!.status)

        transport.down = false
        transport.liveStreams = "[{\"broken\":".encodeToByteArray()
        assertEquals(ImportStatus.FAILED, service.refreshLive(added.playlistId).status)
        assertEquals(3, content.channels(added.playlistId).size, "a truncated list never replaces the snapshot")

        service.deleteSource(added.playlistId)
        assertTrue(content.sources().isEmpty())
        assertTrue(secrets.entries.isEmpty())
    }
}
