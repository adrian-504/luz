package app.iptvplayer.platform.apple

import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.ports.HttpMethod
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpResponse
import app.iptvplayer.domain.ports.HttpTimeouts
import app.iptvplayer.domain.ports.TransportException
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SensitiveUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The iPhone transport keeps Android's rules: redirects returned not followed, body capped, failures classified. */
class UrlSessionTransportTest {
    private val server = TestHttpServer()
    private val transport = UrlSessionTransport()
    private val timeouts = HttpTimeouts(5.seconds, 5.seconds, 10.seconds)

    @AfterTest
    fun stop() = server.stop()

    private fun request(url: String, maxBytes: Long = Long.MAX_VALUE, t: HttpTimeouts = timeouts, method: HttpMethod = HttpMethod.GET) =
        HttpRequest(method, SensitiveUrl.of(url), timeouts = t, maxBodyBytes = maxBytes)

    private suspend fun readAll(response: HttpResponse): ByteArray = withContext(Dispatchers.IO) {
        val out = ArrayList<Byte>()
        val buffer = ByteArray(8192)
        while (true) {
            val n = response.body.read(buffer, 0, buffer.size)
            if (n < 0) break
            for (i in 0 until n) out.add(buffer[i])
        }
        response.body.close()
        out.toByteArray()
    }

    @Test
    fun statusBodyRedirectAndCap() = runBlocking {
        val ok = transport.execute(request(server.url("/ok")))
        assertEquals(200, ok.status)
        assertEquals("yes", ok.headers.entries.first { it.key.equals("X-Test", true) }.value.single())
        assertEquals("hello luz", readAll(ok).decodeToString())

        val big = readAll(transport.execute(request(server.url("/big"))))
        assertEquals(TestHttpServer.BIG, big.size, "the whole body arrives")
        assertTrue(big.withIndex().all { (i, b) -> b == ('a'.code + i % 26).toByte() }, "unchanged and in order")

        val missing = transport.execute(request(server.url("/missing")))
        assertEquals(404, missing.status)
        missing.body.close()

        val redirect = transport.execute(request(server.url("/redirect")))
        assertEquals(302, redirect.status, "redirects are returned, never followed")
        assertEquals("/ok", redirect.headers.entries.first { it.key.equals("Location", true) }.value.single())
        redirect.body.close()

        assertEquals(1_000, readAll(transport.execute(request(server.url("/big"), maxBytes = 1_000))).size, "body capped at maxBodyBytes")
    }

    @Test
    fun headersAreSentAndAProviderNeverSeesAStrangersUserAgent() = runBlocking {
        val sent = HttpRequest(
            HttpMethod.GET,
            SensitiveUrl.of(server.url("/headers")),
            headers = mapOf("Accept" to "application/json"),
            sensitiveHeaders = mapOf("Authorization" to Secret("Bearer canary")),
            timeouts = timeouts,
            maxBodyBytes = 1_000,
        )
        transport.execute(sent).body.close()
        val head = server.lastHeaders.lowercase()
        assertTrue("authorization: bearer canary" in head, "sensitive headers are sent")
        assertTrue("accept: application/json" in head)
        assertTrue("user-agent: iptvplayer/0.1 (ios)" in head, "the app's own user agent")
        assertTrue("cookie" !in head, "no cookies")

        val head2 = transport.execute(request(server.url("/ok"), method = HttpMethod.HEAD))
        assertEquals(200, head2.status)
        assertEquals(0, readAll(head2).size, "HEAD has no body")
    }

    @Test
    fun networkFailuresAreClassified() = runBlocking {
        assertEquals(
            NetworkErrorKind.DNS,
            assertFailsWith<TransportException> { transport.execute(request("http://stream.invalid/x")) }.kind,
        )
        val closed = TestHttpServer().also { it.stop() }.port
        assertEquals(
            NetworkErrorKind.CONNECTION_REFUSED,
            assertFailsWith<TransportException> { transport.execute(request("http://127.0.0.1:$closed/x")) }.kind,
        )
        val short = HttpTimeouts(1.seconds, 300.milliseconds, 1.seconds)
        assertEquals(
            NetworkErrorKind.TIMEOUT,
            assertFailsWith<TransportException> { transport.execute(request(server.url("/slow"), t = short)) }.kind,
        )
    }
}
