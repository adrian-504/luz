package app.iptvplayer.platform.playback

import android.graphics.SurfaceTexture
import android.os.Process
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.playback.PlaybackErrorCode
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.protocols.media.ResolvedMediaSource
import app.iptvplayer.testing.TestMediaServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Real Media3 playback on a device or emulator against [TestMediaServer] (synthetic media, injected faults): the controller
 * must reach the states of docs/PLAYBACK.md §3 and classify failures per §5.
 */
@RunWith(AndroidJUnit4::class)
class Media3PlaybackControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var server: TestMediaServer
    private lateinit var controller: Media3PlaybackController
    private lateinit var texture: SurfaceTexture
    private lateinit var surface: Surface

    private fun setUp(timeouts: PlaybackTimeouts = PlaybackTimeouts()) {
        onMain {
            controller = Media3PlaybackController(instrumentation.targetContext, timeouts)
            controller.attachSurface(surface)
        }
    }

    @Before
    fun startServer() {
        server = TestMediaServer(instrumentation.context.assets)
        texture = SurfaceTexture(false)
        surface = Surface(texture)
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) onMain { controller.release() }
        if (::server.isInitialized) server.close()
        surface.release()
        texture.release()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun play(url: String, protocol: StreamProtocol, mode: PlaybackMode, start: Duration? = null) = onMain {
        controller.prepare(PlaybackRequest(ResolvedMediaSource(SensitiveUrl.of(url), protocol, emptyMap(), emptyMap()), mode, start))
    }

    private fun await(state: PlaybackState, timeout: Duration = 15.seconds): PlaybackSnapshot = runBlocking {
        try {
            withTimeout(timeout) { controller.snapshot.first { it.state == state } }
        } catch (e: Exception) {
            throw AssertionError("expected $state within $timeout, was ${controller.snapshot.value} ${controller.diagnostics.value}", e)
        }
    }

    private fun timing(label: String) {
        val d = controller.diagnostics.value
        Log.i("PlaybackTiming", "$label ttff=${d.timeToFirstFrameMs}ms firstAudio=${d.timeToFirstAudioMs}ms")
    }

    @Test
    fun progressiveMp4VodPausesSeeksEndsAndReplays() {
        setUp()
        play(server.url("/vod.mp4"), StreamProtocol.PROGRESSIVE_MP4, PlaybackMode.VOD)
        await(PlaybackState.PLAYING)
        timing("mp4-vod")
        val diagnostics = controller.diagnostics.value
        assertNotNull(diagnostics.timeToFirstFrameMs, "$diagnostics")
        assertEquals("426x240", diagnostics.resolution)
        assertTrue(diagnostics.videoCodec.orEmpty().startsWith("avc1"), "${diagnostics.videoCodec}")
        assertEquals("http", diagnostics.connection)
        assertEquals(10.seconds.inWholeSeconds, onMain { controller.duration() }?.inWholeSeconds)

        onMain { controller.pause() }
        await(PlaybackState.PAUSED)
        onMain { controller.seekTo(8.seconds) }
        assertEquals(PlaybackState.PAUSED, controller.snapshot.value.state)
        onMain { controller.play() }
        await(PlaybackState.ENDED, 10.seconds)

        onMain { controller.seekTo(Duration.ZERO) }
        await(PlaybackState.PLAYING)
    }

    @Test
    fun hlsVodAndLivePlay() {
        setUp()
        play(server.url("/hls/vod.m3u8"), StreamProtocol.HLS, PlaybackMode.VOD)
        await(PlaybackState.PLAYING)
        timing("hls-vod")
        assertEquals(30L, onMain { controller.duration() }?.inWholeSeconds)

        play(server.url("/hls/live.m3u8"), StreamProtocol.HLS, PlaybackMode.LIVE)
        assertEquals(PlaybackState.PREPARING, controller.snapshot.value.state, "a channel change restarts preparation")
        await(PlaybackState.PLAYING)
        timing("hls-live")
        assertNull(onMain { controller.duration() })
        // Live positions are relative to the moving window, so progress is checked through newly fetched segments.
        fun liveSegments() = server.requestedPaths().count { "?live=" in it }
        val segmentsBefore = liveSegments()
        Thread.sleep(6_000)
        assertEquals(PlaybackState.PLAYING, controller.snapshot.value.state)
        assertTrue(liveSegments() >= segmentsBefore + 2, "live playback keeps fetching new segments")
    }

    @Test
    fun continuousTsLiveStreamPlays() {
        setUp()
        play(server.url("/live.ts"), StreamProtocol.PROGRESSIVE_TS, PlaybackMode.LIVE)
        await(PlaybackState.PLAYING)
        timing("ts-live")
        Thread.sleep(6_000)
        assertEquals(PlaybackState.PLAYING, controller.snapshot.value.state)
        assertEquals(0, controller.snapshot.value.retryAttempt)
    }

    @Test
    fun httpFailuresAreClassifiedAndNotRetried() {
        setUp()
        play(server.url("/missing.m3u8"), StreamProtocol.HLS, PlaybackMode.LIVE)
        // Definitive client errors are reported at once, not after the engine's load retries.
        val notFound = await(PlaybackState.ERROR, 3.seconds)
        assertEquals(PlaybackErrorCode.HTTP_NOT_FOUND, notFound.error)
        assertFalse(notFound.retryPending)
        assertEquals(404, controller.diagnostics.value.httpStatus)

        server.failNext("/hls/vod.m3u8", 401, count = 100)
        play(server.url("/hls/vod.m3u8"), StreamProtocol.HLS, PlaybackMode.VOD)
        val auth = await(PlaybackState.ERROR, 3.seconds)
        assertEquals(PlaybackErrorCode.HTTP_AUTH, auth.error)
        Thread.sleep(3_000)
        assertEquals(PlaybackState.ERROR, controller.snapshot.value.state, "authentication failures are not retried")
    }

    @Test
    fun malformedAndUnsupportedSources() {
        setUp()
        val cases = listOf(
            Triple("/garbage.m3u8", StreamProtocol.HLS, PlaybackErrorCode.SRC_MANIFEST_MALFORMED),
            Triple("/noise.ts", StreamProtocol.PROGRESSIVE_TS, PlaybackErrorCode.SRC_UNSUPPORTED_CONTAINER),
            Triple("/html.ts", StreamProtocol.PROGRESSIVE_TS, PlaybackErrorCode.SRC_UNSUPPORTED_CONTAINER),
            Triple("rtmp://127.0.0.1/live/stream", StreamProtocol.RTMP, PlaybackErrorCode.SRC_UNSUPPORTED_PROTOCOL),
        )
        for ((path, protocol, expected) in cases) {
            play(if (path.startsWith("/")) server.url(path) else path, protocol, PlaybackMode.VOD)
            val snapshot = await(PlaybackState.ERROR)
            assertEquals(expected, snapshot.error, path)
            assertFalse(snapshot.retryPending, path)
        }
    }

    @Test
    fun transientServerErrorsRecoverAutomatically() {
        setUp()
        server.failNext("/hls/vod.m3u8", 503, count = 6)
        play(server.url("/hls/vod.m3u8"), StreamProtocol.HLS, PlaybackMode.VOD)
        await(PlaybackState.PLAYING, 40.seconds)
        val manifestRequests = server.requestedPaths().count { it.startsWith("/hls/vod.m3u8") }
        Log.i(
            "PlaybackTiming",
            "503x6 recovered after $manifestRequests manifest requests, controller retries=${controller.diagnostics.value.retryCount}",
        )
        assertTrue(manifestRequests >= 7)
    }

    @Test
    fun dnsFailureIsClassifiedAndRetried() {
        setUp()
        play("http://stream.invalid/live/1.ts", StreamProtocol.PROGRESSIVE_TS, PlaybackMode.LIVE)
        val snapshot = await(PlaybackState.ERROR, 20.seconds)
        assertEquals(PlaybackErrorCode.NET_DNS, snapshot.error)
        assertTrue(snapshot.retryPending)
    }

    @Test
    fun stalledLiveStreamTimesOutRetriesAndRecovers() {
        setUp(PlaybackTimeouts(stallLive = 3.seconds))
        play(server.url("/hls/live.m3u8"), StreamProtocol.HLS, PlaybackMode.LIVE)
        await(PlaybackState.PLAYING)
        server.stalled = true
        await(PlaybackState.BUFFERING, 30.seconds)
        val error = await(PlaybackState.ERROR, 10.seconds)
        assertEquals(PlaybackErrorCode.TIMEOUT_STALL, error.error, "${controller.diagnostics.value}")
        assertTrue(error.retryPending)
        server.stalled = false
        await(PlaybackState.PLAYING, 30.seconds)
        assertTrue(controller.diagnostics.value.retryCount >= 1)
    }

    @Test
    fun slowSourceTimesOutWhilePreparingThenRecovers() {
        setUp(PlaybackTimeouts(prepareLive = 2.seconds))
        server.responseDelayMs = 6_000
        play(server.url("/hls/live.m3u8"), StreamProtocol.HLS, PlaybackMode.LIVE)
        assertEquals(PlaybackErrorCode.TIMEOUT_PREPARE, await(PlaybackState.ERROR, 5.seconds).error)
        server.responseDelayMs = 0
        await(PlaybackState.PLAYING, 30.seconds)
    }

    @Test
    fun credentialsInStreamUrlsNeverReachTheLog() {
        setUp()
        val marker = "log-capture-marker-${System.nanoTime()}"
        Log.i("PlaybackTest", marker)
        play(server.url("/live/$CANARY_USER/$CANARY_PASSWORD/1.ts"), StreamProtocol.PROGRESSIVE_TS, PlaybackMode.LIVE)
        await(PlaybackState.ERROR)
        play(
            "http://canary.invalid/live/$CANARY_USER/$CANARY_PASSWORD/2.m3u8?token=$CANARY_PASSWORD",
            StreamProtocol.HLS,
            PlaybackMode.LIVE,
        )
        await(PlaybackState.ERROR, 20.seconds)
        onMain { controller.stop() }

        val log = ProcessBuilder(
            "logcat",
            "-d",
            "--pid=${Process.myPid()}",
        ).redirectErrorStream(true).start().inputStream.bufferedReader().readText()
        assertTrue(marker in log, "logcat capture works")
        assertFalse(CANARY_PASSWORD in log, "password leaked to logcat")
        assertFalse(CANARY_USER in log, "username leaked to logcat")
    }

    private companion object {
        const val CANARY_USER = "canary-user"
        const val CANARY_PASSWORD = "CANARY-PW-7f3a9c-DO-NOT-LOG"
    }
}
