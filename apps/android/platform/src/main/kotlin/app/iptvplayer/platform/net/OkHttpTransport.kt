package app.iptvplayer.platform.net

import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.domain.ports.HttpMethod
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpResponse
import app.iptvplayer.domain.ports.HttpTimings
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [HttpTransport] on OkHttp (ADR-0014, ADR-0026). Redirects are never followed here: the shared `HttpFetcher`
 * checks every hop against the URL policy. Nothing is logged. Response bodies are streamed and capped at
 * [HttpRequest.maxBodyBytes]; the stream ends there and the parsers report the truncation.
 */
class OkHttpTransport(private val networkAvailable: () -> Boolean = { true }, base: OkHttpClient = OkHttpClient()) : HttpTransport {
    private val client = base.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val call = client.newBuilder()
            .connectTimeout(request.timeouts.connect.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(request.timeouts.idleRead.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .callTimeout(request.timeouts.total.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()
            .newCall(toOkHttp(request))
        val started = System.nanoTime()
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw TransportException(kindOf(e), e)
        }
        HttpResponse(
            status = response.code,
            headers = response.headers.toMultimap(),
            url = request.url,
            body = CappedBody(call, response, request.maxBodyBytes),
            timings = HttpTimings(started, System.nanoTime()),
        )
    }

    private fun toOkHttp(request: HttpRequest): Request {
        val builder = Request.Builder().url(request.url.unsafeRawValue())
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        request.sensitiveHeaders.forEach { (name, value) -> builder.header(name, value.unsafeValue()) }
        request.conditional?.etag?.let { builder.header("If-None-Match", it) }
        request.conditional?.lastModified?.let { builder.header("If-Modified-Since", it) }
        if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) builder.header("User-Agent", USER_AGENT)
        return when (request.method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.HEAD -> builder.head()
        }.build()
    }

    private fun kindOf(e: IOException): NetworkErrorKind = when {
        !networkAvailable() -> NetworkErrorKind.OFFLINE
        e is UnknownHostException -> NetworkErrorKind.DNS
        e is SSLException -> NetworkErrorKind.TLS
        e is ConnectException -> NetworkErrorKind.CONNECTION_REFUSED
        e is InterruptedIOException -> NetworkErrorKind.TIMEOUT
        e is SocketException -> NetworkErrorKind.CONNECTION_RESET
        else -> NetworkErrorKind.UNKNOWN
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) = continuation.resume(response) { _, _, _ -> response.close() }

                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
            },
        )
    }

    private inner class CappedBody(private val call: Call, private val response: Response, private val maxBytes: Long) : ByteSource {
        private val stream: InputStream = response.body.byteStream()
        private var consumed = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val remaining = maxBytes - consumed
            if (remaining <= 0) return -1
            val count = try {
                stream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            } catch (e: IOException) {
                throw TransportException(kindOf(e), e)
            }
            if (count > 0) consumed += count
            return count
        }

        override fun close() {
            response.close()
            if (consumed < maxBytes) call.cancel()
        }
    }

    private companion object {
        const val USER_AGENT = "IPTVPlayer/0.1 (Android)"
    }
}
