package app.iptvplayer.platform.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.ports.HttpMethod
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpTimeouts
import app.iptvplayer.domain.ports.TransportException
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.testing.TestMediaServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class OkHttpTransportTest {
    private val server = TestMediaServer(InstrumentationRegistry.getInstrumentation().context.assets)
    private val transport = OkHttpTransport()
    private val timeouts = HttpTimeouts(5.seconds, 5.seconds, 20.seconds)

    @After
    fun stop() = server.close()

    private fun request(url: String, maxBytes: Long = Long.MAX_VALUE, t: HttpTimeouts = timeouts) =
        HttpRequest(HttpMethod.GET, SensitiveUrl.of(url), timeouts = t, maxBodyBytes = maxBytes)

    private fun readAll(response: app.iptvplayer.domain.ports.HttpResponse): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = response.body.read(buffer, 0, buffer.size)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        response.body.close()
        return out.toByteArray()
    }

    @Test
    fun statusBodyRedirectAndCap() = runBlocking {
        val ok = transport.execute(request(server.url("/vod.mp4")))
        assertEquals(200, ok.status)
        val expected = InstrumentationRegistry.getInstrumentation().context.assets.open("vod-10s.mp4").use { it.readBytes() }
        assertTrue(expected.contentEquals(readAll(ok)), "the whole body arrives unchanged")

        val missing = transport.execute(request(server.url("/missing")))
        assertEquals(404, missing.status)
        missing.body.close()

        val redirect = transport.execute(request(server.url("/redirect")))
        assertEquals(302, redirect.status, "redirects are returned, never followed")
        assertEquals("/vod.mp4", redirect.headers.entries.first { it.key.equals("Location", true) }.value.single())
        redirect.body.close()

        assertEquals(
            1_000,
            readAll(transport.execute(request(server.url("/vod.mp4"), maxBytes = 1_000))).size,
            "body capped at maxBodyBytes",
        )
    }

    @Test
    fun networkFailuresAreClassified() = runBlocking {
        assertEquals(
            NetworkErrorKind.DNS,
            assertFailsWith<TransportException> { transport.execute(request("http://stream.invalid/x")) }.kind,
        )
        val closedPort = ServerSocket(0).use { it.localPort }
        assertEquals(
            NetworkErrorKind.CONNECTION_REFUSED,
            assertFailsWith<TransportException> {
                transport.execute(request("http://127.0.0.1:$closedPort/x"))
            }.kind,
        )
        server.responseDelayMs = 2_000
        val short = HttpTimeouts(1.seconds, 300.milliseconds, 1.seconds)
        assertEquals(
            NetworkErrorKind.TIMEOUT,
            assertFailsWith<TransportException> {
                transport.execute(request(server.url("/vod.mp4"), t = short))
            }.kind,
        )
    }
}
