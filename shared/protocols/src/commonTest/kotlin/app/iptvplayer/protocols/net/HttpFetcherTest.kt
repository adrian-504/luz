package app.iptvplayer.protocols.net

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.net.UrlFlag
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.protocols.testing.FakeTransport
import app.iptvplayer.protocols.testing.FakeTransport.Reply
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class HttpFetcherTest {
    private val url = SensitiveUrl.of(
        "https://provider.example.com/player_api.php?username=canary-user&password=CANARY-PW-7f3a9c-DO-NOT-LOG",
    )

    private val sleeps = ArrayList<Duration>()

    private fun fetcher(transport: FakeTransport) = HttpFetcher(transport, jitter = { 0.0 }, sleep = { sleeps += it })

    @Test
    fun successReturnsResponseAndFlags() = runTest {
        val transport = FakeTransport { FakeTransport.ok("[]") }
        val result = assertIs<FetchResult.Success>(fetcher(transport).get(url, RequestClass.METADATA, UrlContext.API))
        assertEquals(1, result.attempts)
        assertTrue(UrlFlag.CLEARTEXT !in result.flags)
        assertEquals(RequestClass.METADATA.timeouts, transport.requests.single().timeouts)
        assertEquals(RequestClass.METADATA.maxBodyBytes, transport.requests.single().maxBodyBytes)
        result.response.body.close()
        assertEquals(0, transport.openBodies)
    }

    @Test
    fun transientFailuresRetryWithBackoffThenSucceed() = runTest {
        var calls = 0
        val transport = FakeTransport {
            when (++calls) {
                1 -> Reply.Fail(NetworkErrorKind.TIMEOUT)
                2 -> FakeTransport.status(503)
                else -> FakeTransport.ok("[]")
            }
        }
        val result = assertIs<FetchResult.Success>(fetcher(transport).get(url, RequestClass.METADATA, UrlContext.API))
        assertEquals(3, result.attempts)
        assertEquals(listOf(1.seconds, 3.seconds), sleeps, "backoff schedule for METADATA")
        result.response.body.close()
        assertEquals(0, transport.openBodies, "failed attempt bodies are closed")
    }

    @Test
    fun retriesAreBoundedPerRequestClass() = runTest {
        val transport = FakeTransport { FakeTransport.status(500) }
        val result = assertIs<FetchResult.Failure>(fetcher(transport).get(url, RequestClass.LARGE_LIST, UrlContext.API))
        assertEquals(DomainError.Http(500), result.error)
        assertEquals(2, result.attempts)
        assertEquals(listOf(2.seconds), sleeps)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun retryAfterIsHonouredAndCapped() = runTest {
        var calls = 0
        val transport = FakeTransport {
            if (++calls == 1) Reply.Body(429, ByteArray(0), mapOf("retry-after" to listOf("600"))) else FakeTransport.ok("[]")
        }
        assertIs<FetchResult.Success>(fetcher(transport).get(url, RequestClass.METADATA, UrlContext.API)).response.body.close()
        assertEquals(listOf(30.seconds), sleeps, "Retry-After 600 s capped at 30 s")
    }

    @Test
    fun clientErrorsAuthAndNonTransientNetworkFailuresAreNotRetried() = runTest {
        for (status in listOf(400, 401, 403, 404, 501)) {
            val transport = FakeTransport { FakeTransport.status(status) }
            val result = assertIs<FetchResult.Failure>(fetcher(transport).get(url, RequestClass.METADATA, UrlContext.API))
            assertEquals(1, transport.requests.size, "status $status")
            assertEquals(status, result.status)
        }
        for (kind in listOf(NetworkErrorKind.DNS, NetworkErrorKind.TLS, NetworkErrorKind.OFFLINE)) {
            val transport = FakeTransport { Reply.Fail(kind) }
            assertEquals(
                DomainError.Network(kind),
                assertIs<FetchResult.Failure>(fetcher(transport).get(url, RequestClass.METADATA, UrlContext.API)).error,
            )
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun redirectsAreFollowedManuallyAndCheckedByPolicy() = runTest {
        val transport = FakeTransport { request ->
            when {
                request.url.unsafeRawValue().startsWith(
                    "https://provider.example.com/",
                ) -> Reply.Body(302, ByteArray(0), mapOf("Location" to listOf("https://cdn.example.net/moved?x=1")))
                else -> FakeTransport.ok("[]")
            }
        }
        val result = assertIs<FetchResult.Success>(fetcher(transport).get(url, RequestClass.METADATA, UrlContext.API))
        assertEquals("https://cdn.example.net/moved?x=1", transport.requests.last().url.unsafeRawValue())
        result.response.body.close()
        assertEquals(0, transport.openBodies)
    }

    @Test
    fun httpsToHttpRedirectAndRedirectLoopsAreRejected() = runTest {
        val downgrade = FakeTransport { Reply.Body(301, ByteArray(0), mapOf("Location" to listOf("http://provider.example.com/plain"))) }
        assertEquals(
            DomainError.Validation(ValidationFailure.URL_REJECTED),
            assertIs<FetchResult.Failure>(fetcher(downgrade).get(url, RequestClass.METADATA, UrlContext.API)).error,
        )
        val loop = FakeTransport { Reply.Body(302, ByteArray(0), mapOf("Location" to listOf("/again"))) }
        assertIs<FetchResult.Failure>(fetcher(loop).get(url, RequestClass.METADATA, UrlContext.API))
        assertEquals(6, loop.requests.size, "initial request plus five redirects")
    }

    @Test
    fun disallowedSchemesNeverReachTheTransport() = runTest {
        val transport = FakeTransport { FakeTransport.ok("[]") }
        val result =
            assertIs<FetchResult.Failure>(
                fetcher(transport).get(SensitiveUrl.of("file:///etc/passwd"), RequestClass.METADATA, UrlContext.API),
            )
        assertEquals(DomainError.Validation(ValidationFailure.URL_REJECTED), result.error)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun errorsNeverContainCredentials() = runTest {
        val transport = FakeTransport { Reply.Fail(NetworkErrorKind.TIMEOUT) }
        val result = fetcher(transport).get(url, RequestClass.METADATA, UrlContext.API)
        assertTrue(!result.toString().contains("CANARY"), result.toString())
    }
}
