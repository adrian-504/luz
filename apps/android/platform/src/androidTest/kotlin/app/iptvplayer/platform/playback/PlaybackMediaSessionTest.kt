package app.iptvplayer.platform.playback

import android.graphics.SurfaceTexture
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.protocols.media.ResolvedMediaSource
import app.iptvplayer.testing.TestMediaServer
import app.iptvplayer.testing.TestPanel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The system media session drives the player through the controller and never exposes the stream address. */
@RunWith(AndroidJUnit4::class)
class PlaybackMediaSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = TestMediaServer(instrumentation.context.assets)
    private val texture = SurfaceTexture(false)
    private val surface = Surface(texture)
    private lateinit var controller: Media3PlaybackController
    private lateinit var session: PlaybackMediaSession
    private var mediaController: MediaController? = null
    private val skips = Collections.synchronizedList(mutableListOf<Int>())

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun await(state: PlaybackState) = runBlocking {
        try {
            withTimeout(15.seconds) { controller.snapshot.first { it.state == state } }
        } catch (e: Exception) {
            throw AssertionError("expected $state, was ${controller.snapshot.value} ${controller.diagnostics.value}", e)
        }
    }

    private fun awaitTrue(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (!onMain(condition)) {
            if (System.nanoTime() > deadline) throw AssertionError("$label not reached")
            Thread.sleep(50)
        }
    }

    @After
    fun tearDown() {
        onMain {
            mediaController?.release()
            if (::session.isInitialized) session.close()
            if (::controller.isInitialized) controller.release()
        }
        server.close()
        surface.release()
        texture.release()
    }

    @Test
    fun systemControlsPauseResumeAndSwitchChannelsWithoutSeeingTheStreamAddress() {
        val context = instrumentation.targetContext
        onMain {
            controller = Media3PlaybackController(context)
            controller.attachSurface(surface)
            session = PlaybackMediaSession(context, controller, onSkip = { skips += it })
        }
        val url = server.url("/live/${TestPanel.USERNAME}/${TestPanel.PASSWORD}/101.ts")
        onMain {
            val source = ResolvedMediaSource(SensitiveUrl.of(url), StreamProtocol.PROGRESSIVE_TS, emptyMap(), emptyMap())
            controller.prepare(PlaybackRequest(source, PlaybackMode.LIVE, title = "Test channel", subtitle = "Test programme"))
        }
        await(PlaybackState.PLAYING)

        val future = onMain { MediaController.Builder(context, session.token).buildAsync() }
        val remote = future.get(10, TimeUnit.SECONDS).also { mediaController = it }
        awaitTrue("title published") { remote.mediaMetadata.title?.toString() == "Test channel" }
        assertFalse(onMain { remote.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) }, "live channels cannot seek")
        assertTrue(onMain { remote.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) }, "next channel is offered")

        onMain { remote.pause() }
        await(PlaybackState.PAUSED)
        onMain { remote.play() }
        await(PlaybackState.PLAYING)

        onMain { remote.seekToNext() }
        awaitTrue("next channel requested") { skips.toList() == listOf(1) }
        onMain { remote.seekToPrevious() }
        awaitTrue("previous channel requested") { skips.toList() == listOf(1, -1) }
        assertEquals(PlaybackState.PLAYING, controller.snapshot.value.state)

        val dump = shell("dumpsys media_session")
        assertTrue("Test channel" in dump, "the session is registered with the system")
        for (secret in listOf(TestPanel.USERNAME, TestPanel.PASSWORD, "127.0.0.1", "/live/")) {
            assertFalse(secret in dump, "stream address or credential visible in the system media session")
        }
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }
}
