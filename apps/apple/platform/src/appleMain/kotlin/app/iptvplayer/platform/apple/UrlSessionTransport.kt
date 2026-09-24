package app.iptvplayer.platform.apple

import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.domain.ports.HttpMethod
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpResponse
import app.iptvplayer.domain.ports.HttpTimings
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.TransportException
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCondition
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLErrorAppTransportSecurityRequiresSecureConnection
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorServerCertificateHasBadDate
import platform.Foundation.NSURLErrorServerCertificateHasUnknownRoot
import platform.Foundation.NSURLErrorServerCertificateNotYetValid
import platform.Foundation.NSURLErrorServerCertificateUntrusted
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

/**
 * Apple [HttpTransport] on URLSession, held to the same rules as Android's (ADR-0014, ADR-0026): redirects are never
 * followed — the 3xx comes back and the shared `HttpFetcher` checks the next hop against the URL policy; the request's
 * timeouts and body cap are enforced; no cookies, no cache; nothing is logged. The body is streamed: bytes are handed to
 * the parser as they arrive, so a large guide never sits in memory whole.
 *
 * URLSession has no separate connect timeout: [HttpRequest.timeouts] `idleRead` bounds the wait for any bytes, including
 * the first, and `total` bounds the whole exchange.
 */
public class UrlSessionTransport(private val networkAvailable: () -> Boolean = { true }) : HttpTransport {
    private val clock = TimeSource.Monotonic
    private val started = clock.markNow()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            timeoutIntervalForRequest = request.timeouts.idleRead.inWholeMilliseconds / 1000.0
            timeoutIntervalForResource = request.timeouts.total.inWholeMilliseconds / 1000.0
            requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            URLCache = null
            HTTPCookieStorage = null
            HTTPShouldSetCookies = false
        }
        val body = StreamingBody(request.maxBodyBytes)
        val delegate = SessionDelegate(body, ::kindOf)
        val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
        val session = NSURLSession.sessionWithConfiguration(configuration, delegate, queue)
        val task = session.dataTaskWithRequest(toUrlRequest(request))
        body.session = session
        val requestStart = nanos()
        val response = suspendCancellableCoroutine { continuation ->
            delegate.onResponse = { result -> result.fold(continuation::resume, continuation::resumeWithException) }
            continuation.invokeOnCancellation { body.close() }
            task.resume()
        }
        return HttpResponse(
            status = response.statusCode.toInt(),
            headers = response.allHeaderFields.entries.associate { (name, value) -> name.toString() to listOf(value.toString()) },
            url = request.url,
            body = body,
            timings = HttpTimings(requestStart, nanos()),
        )
    }

    private fun nanos(): Long = started.elapsedNow().inWholeNanoseconds

    private fun toUrlRequest(request: HttpRequest): NSMutableURLRequest {
        val url = NSURL.URLWithString(request.url.unsafeRawValue()) ?: throw TransportException(NetworkErrorKind.UNKNOWN)
        return NSMutableURLRequest.requestWithURL(url).apply {
            setHTTPMethod(if (request.method == HttpMethod.HEAD) "HEAD" else "GET")
            request.headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
            request.sensitiveHeaders.forEach { (name, value) -> setValue(value.unsafeValue(), forHTTPHeaderField = name) }
            request.conditional?.etag?.let { setValue(it, forHTTPHeaderField = "If-None-Match") }
            request.conditional?.lastModified?.let { setValue(it, forHTTPHeaderField = "If-Modified-Since") }
            if (request.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                setValue(USER_AGENT, forHTTPHeaderField = "User-Agent")
            }
        }
    }

    private fun kindOf(error: NSError): NetworkErrorKind = when {
        !networkAvailable() -> NetworkErrorKind.OFFLINE
        error.domain != NSURLErrorDomain -> NetworkErrorKind.UNKNOWN
        else -> when (error.code) {
            NSURLErrorNotConnectedToInternet -> NetworkErrorKind.OFFLINE
            NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed -> NetworkErrorKind.DNS
            NSURLErrorTimedOut -> NetworkErrorKind.TIMEOUT
            NSURLErrorCannotConnectToHost -> NetworkErrorKind.CONNECTION_REFUSED
            NSURLErrorNetworkConnectionLost -> NetworkErrorKind.CONNECTION_RESET
            NSURLErrorSecureConnectionFailed,
            NSURLErrorServerCertificateHasBadDate,
            NSURLErrorServerCertificateUntrusted,
            NSURLErrorServerCertificateHasUnknownRoot,
            NSURLErrorServerCertificateNotYetValid,
            NSURLErrorAppTransportSecurityRequiresSecureConnection,
            -> NetworkErrorKind.TLS
            else -> NetworkErrorKind.UNKNOWN
        }
    }

    private companion object {
        const val USER_AGENT = "IPTVPlayer/0.1 (iOS)"
    }
}

/**
 * One response body: URLSession's callbacks append to it on the session's queue; the parser reads from it on its own
 * thread, waiting on [condition] until bytes, the end or an error arrive. Past the body cap the task is cancelled and the
 * stream simply ends, as on Android.
 */
private class StreamingBody(private val maxBytes: Long) : ByteSource {
    var session: NSURLSession? = null

    private val condition = NSCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var chunkOffset = 0
    private var received = 0L
    private var finished = false
    private var failure: TransportException? = null

    /** Adds [bytes]; returns false once the cap is reached, when the caller cancels the task. */
    fun append(bytes: ByteArray): Boolean = locked {
        val room = maxBytes - received
        if (room <= 0) return@locked false
        val kept = if (bytes.size > room) bytes.copyOf(room.toInt()) else bytes
        chunks.addLast(kept)
        received += kept.size
        if (received >= maxBytes) finished = true
        condition.broadcast()
        received < maxBytes
    }

    fun finish(error: TransportException?) = locked {
        if (error != null && !finished) failure = error
        finished = true
        condition.broadcast()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = locked {
        while (chunks.isEmpty() && !finished) condition.wait()
        if (chunks.isEmpty()) {
            failure?.let { throw it }
            return@locked -1
        }
        val chunk = chunks.first()
        val count = minOf(length, chunk.size - chunkOffset)
        chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + count)
        chunkOffset += count
        if (chunkOffset == chunk.size) {
            chunks.removeFirst()
            chunkOffset = 0
        }
        count
    }

    override fun close() {
        locked {
            finished = true
            chunks.clear()
            condition.broadcast()
        }
        session?.invalidateAndCancel()
    }

    private inline fun <T> locked(block: () -> T): T {
        condition.lock()
        try {
            return block()
        } finally {
            condition.unlock()
        }
    }
}

/** URLSession's side of one request: refuses redirects, answers once with the response, feeds the body. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class SessionDelegate(
    private val body: StreamingBody,
    private val kindOf: (NSError) -> NetworkErrorKind,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    var onResponse: ((Result<NSHTTPURLResponse>) -> Unit)? = null
    private val answerLock = NSCondition()
    private var answered = false

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        // Never follow: the 3xx itself is the response.
        completionHandler(null)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        completionHandler(NSURLSessionResponseAllow)
        answer(Result.success(didReceiveResponse as NSHTTPURLResponse))
    }

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        if (!body.append(didReceiveData.toByteArray())) dataTask.cancel()
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val failure = didCompleteWithError?.let { TransportException(kindOf(it)) }
        body.finish(failure)
        // A failure before any response is the request's failure; a 3xx whose redirect was refused has its response.
        val response = task.response as? NSHTTPURLResponse
        answer(if (response != null) Result.success(response) else Result.failure(failure ?: TransportException(NetworkErrorKind.UNKNOWN)))
        session.finishTasksAndInvalidate()
    }

    private fun answer(result: Result<NSHTTPURLResponse>) {
        answerLock.lock()
        val callback = try {
            if (answered) null else onResponse.also { answered = true }
        } finally {
            answerLock.unlock()
        }
        callback?.invoke(result)
    }
}
