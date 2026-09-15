package app.iptvplayer.ingestion

import app.iptvplayer.domain.capability.PlatformCapabilities
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
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
                else -> 200 to "[]".encodeToByteArray()
            }
            url.startsWith("http://panel.example.com:8080/xmltv.php") && credentialsOk -> 200 to Fixtures.smallValidXmltv
            url.startsWith("http://lists.example.com/get.php") && credentialsOk -> 200 to Fixtures.smallValidM3u
            url.startsWith("https://lists.example.com/playlist.m3u?token=$CANARY_PASSWORD") -> 200 to Fixtures.smallValidM3u
            url.startsWith("https://lists.example.com/not-a-playlist") -> 200 to Fixtures.htmlErrorBody
            url.startsWith("https://epg.example.org/small-valid.xml") -> 200 to Fixtures.smallValidXmltv
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
