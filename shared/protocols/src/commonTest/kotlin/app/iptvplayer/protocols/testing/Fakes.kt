package app.iptvplayer.protocols.testing

import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpResponse
import app.iptvplayer.domain.ports.HttpTimings
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.TransportException
import app.iptvplayer.domain.security.SensitiveUrl
import kotlin.time.Instant

/** Scripted transport: routes by request, records every request, and tracks that response bodies get closed. */
class FakeTransport(private val route: (HttpRequest) -> Reply) : HttpTransport {
    sealed interface Reply {
        data class Body(val status: Int, val bytes: ByteArray, val headers: Map<String, List<String>> = emptyMap()) : Reply

        data class Fail(val kind: NetworkErrorKind) : Reply
    }

    val requests = ArrayList<HttpRequest>()
    var openBodies = 0
        private set

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return when (val reply = route(request)) {
            is Reply.Fail -> throw TransportException(reply.kind)
            is Reply.Body -> {
                openBodies++
                val delegate = ByteArraySource(reply.bytes)
                val body = object : ByteSource {
                    private var closed = false

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delegate.read(buffer, offset, length)

                    override fun close() {
                        if (!closed) openBodies--
                        closed = true
                    }
                }
                HttpResponse(reply.status, reply.headers, request.url, body, HttpTimings(0, 0))
            }
        }
    }

    companion object {
        fun ok(bytes: ByteArray) = Reply.Body(200, bytes)

        fun ok(text: String) = Reply.Body(200, text.encodeToByteArray())

        fun status(code: Int, text: String = "") = Reply.Body(code, text.encodeToByteArray())

        /** Query parameter from a request URL (for routing only; tests never print URLs). */
        fun param(url: SensitiveUrl, name: String): String? =
            url.unsafeRawValue().substringAfter('?', "").split('&').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
    }
}

class FixedClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant

    override fun monotonicNanos(): Long = 0
}
