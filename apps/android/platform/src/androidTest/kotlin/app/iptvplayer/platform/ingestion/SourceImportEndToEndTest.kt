package app.iptvplayer.platform.ingestion

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.ingestion.SourceService
import app.iptvplayer.platform.AndroidPlatformCapabilities
import app.iptvplayer.platform.ForegroundRule
import app.iptvplayer.platform.SystemClock
import app.iptvplayer.platform.net.OkHttpTransport
import app.iptvplayer.platform.playback.Media3PlaybackController
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.platform.secrets.KeystoreSecretStore
import app.iptvplayer.protocols.media.ResolveResult
import app.iptvplayer.storage.BundledSqliteDriver
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.storage.db.IptvDatabase
import app.iptvplayer.testing.TestMediaServer
import app.iptvplayer.testing.TestPanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The whole Android stack: OkHttp → shared import → bundled SQLite + Keystore → resolver → Media3, against the test panel. */
@RunWith(AndroidJUnit4::class)
class SourceImportEndToEndTest {
    @get:Rule
    val foreground = ForegroundRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val server = TestMediaServer(instrumentation.context.assets)
    private val dbFile = File(context.cacheDir, "e2e-${System.nanoTime()}.db")
    private val driver = BundledSqliteDriver.open(dbFile.path, IptvDatabase.Schema)
    private val content = ContentStore(driver, SystemClock)
    private val epg = EpgStore(driver)
    private val service =
        SourceService(
            OkHttpTransport(),
            KeystoreSecretStore(context, "iptv-secrets-e2e"),
            content,
            epg,
            SystemClock,
            AndroidPlatformCapabilities,
        )

    @After
    fun cleanUp() {
        driver.close()
        server.close()
        listOf("", "-wal", "-shm").forEach { File(dbFile.path + it).delete() }
    }

    @Test
    fun addXtreamImportGuideAndPlayAChannel() = runBlocking {
        val added = assertIs<AddSourceResult.Added>(service.addXtream("Test panel", server.url(""), TestPanel.USERNAME, TestPanel.PASSWORD))
        assertEquals(ImportStatus.PUBLISHED, added.live.status)
        val channels = content.channels(added.playlistId)
        assertEquals(7, channels.size)
        assertEquals(listOf("News", "Sports", "Documentaries & Kids"), content.groups(added.playlistId).map { it.title })

        assertEquals(ImportStatus.PUBLISHED, service.refreshEpg(added.playlistId).status)
        assertEquals(4L, epg.linkCount(added.playlistId.value), "3 by EPG id, Test Kids by display name")
        val news = channels.first { it.name == "Test News HD" }
        assertNotNull(epg.nowNext(added.playlistId.value, listOf(news.id.value), SystemClock.now())[news.id.value]?.current)

        val resolved = assertIs<ResolveResult.Resolved>(service.resolveChannel(added.playlistId, news.id))
        val texture = SurfaceTexture(false)
        val surface = Surface(texture)
        var controller: Media3PlaybackController? = null
        instrumentation.runOnMainSync {
            controller = Media3PlaybackController(context).also {
                it.attachSurface(surface)
                it.prepare(PlaybackRequest(resolved.source, PlaybackMode.LIVE))
            }
        }
        try {
            withTimeout(15_000) { controller!!.snapshot.first { it.state == PlaybackState.PLAYING } }
        } finally {
            instrumentation.runOnMainSync { controller?.release() }
            surface.release()
            texture.release()
        }

        val bytes = listOf("", "-wal").map {
            File(dbFile.path + it)
        }.filter { it.exists() }.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(TestPanel.PASSWORD in bytes, "password written to the database")
        service.deleteSource(added.playlistId)
        assertTrue(content.sources().isEmpty())
    }

    @Test
    fun xtreamStyleM3uUrlImportsTheSameChannels() = runBlocking {
        val url = server.url("/get.php?username=${TestPanel.USERNAME}&password=${TestPanel.PASSWORD}&type=m3u_plus&output=ts")
        val added = assertIs<AddSourceResult.Added>(service.addM3u(null, url))
        assertEquals(7, content.channels(added.playlistId).size)
        assertEquals(ImportStatus.PUBLISHED, service.refreshEpg(added.playlistId).status, "guide from the playlist's url-tvg")
        service.deleteSource(added.playlistId)
    }
}
