package app.iptvplayer.domain.ports

import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.domain.security.SensitiveUrl
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant

// Interfaces implemented natively per platform (docs/ARCHITECTURE.md §3). Shared code depends on these,
// never on platform APIs.

public interface Clock {
    public fun now(): Instant

    /** Monotonic time for measuring durations; unrelated to wall-clock time. */
    public fun monotonicNanos(): Long
}

/** Keychain (Apple) / Keystore-backed store (Android). The only place secrets are persisted (ADR-0015). */
public interface SecretStore {
    public suspend fun put(ref: CredentialRef, bundle: SecretBundle)

    public suspend fun get(ref: CredentialRef): SecretBundle?

    public suspend fun delete(ref: CredentialRef)

    public suspend fun exists(ref: CredentialRef): Boolean
}

/** Trace names are restricted to `[a-z0-9_.]` so no URL, title or credential can be used as a name. */
@JvmInline
public value class TraceName(public val value: String) {
    init {
        require(value.isNotEmpty() && value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }) {
            "trace names must match [a-z0-9_.]+"
        }
    }
}

/** Span attributes are numeric or boolean only — no free-form strings (docs/PERFORMANCE.md §3). */
public interface TraceSpan {
    public fun setAttribute(key: TraceName, value: Long)

    public fun setAttribute(key: TraceName, value: Boolean)

    public fun end()
}

public interface Tracer {
    public fun beginSpan(name: TraceName): TraceSpan

    public fun recordCounter(name: TraceName, value: Long)
}

/** Shared event names so traces are comparable across platforms (docs/PERFORMANCE.md §3.3). */
public object TraceNames {
    public val PLAYBACK_INTENT: TraceName = TraceName("playback.intent")
    public val PLAYBACK_PREPARE_CALLED: TraceName = TraceName("playback.prepare_called")
    public val PLAYBACK_REQUEST_SENT: TraceName = TraceName("playback.request_sent")
    public val PLAYBACK_FIRST_BYTE: TraceName = TraceName("playback.first_byte")
    public val PLAYBACK_FIRST_FRAME: TraceName = TraceName("playback.first_frame")
    public val PLAYBACK_FIRST_AUDIO: TraceName = TraceName("playback.first_audio")
    public val LAUNCH_CONTENT_VISIBLE: TraceName = TraceName("launch.content_visible")
    public val IMPORT_UNIT: TraceName = TraceName("import.unit")
    public val SEARCH_QUERY: TraceName = TraceName("search.query")
    public val EPG_WINDOW_QUERY: TraceName = TraceName("epg.window_query")
}

// HTTP transport. Provisional byte-stream abstraction: Phase 2 decides between Okio and kotlinx-io by ADR and may
// replace ByteSource.

public interface ByteSource {
    /** Reads up to [length] bytes into [buffer]; returns the count, or -1 at end of stream. */
    public fun read(buffer: ByteArray, offset: Int, length: Int): Int

    public fun close()
}

/** In-memory [ByteSource] (file imports read fully by the platform picker, tests). */
public class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    private var position = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }

    override fun close() {}
}

public enum class HttpMethod { GET, HEAD }

public data class ConditionalHeaders(public val etag: String?, public val lastModified: String?)

public data class HttpTimeouts(public val connect: Duration, public val idleRead: Duration, public val total: Duration)

public class HttpRequest(
    public val method: HttpMethod,
    public val url: SensitiveUrl,
    public val headers: Map<String, String> = emptyMap(),
    public val sensitiveHeaders: Map<String, Secret<String>> = emptyMap(),
    public val conditional: ConditionalHeaders? = null,
    public val timeouts: HttpTimeouts,
    public val maxBodyBytes: Long,
)

public class HttpTimings(public val requestStartNanos: Long, public val responseHeadersNanos: Long?)

public class HttpResponse(
    public val status: Int,
    public val headers: Map<String, List<String>>,
    public val url: SensitiveUrl,
    public val body: ByteSource,
    public val timings: HttpTimings,
)

/**
 * Implementations must not follow redirects (every hop goes through UrlPolicy.checkRedirect), must enforce
 * [HttpRequest.timeouts] and [HttpRequest.maxBodyBytes], and must never log URLs or headers unredacted.
 */
public interface HttpTransport {
    /** @throws TransportException for network-level failures. */
    public suspend fun execute(request: HttpRequest): HttpResponse
}

/** The message never contains a URL, host or header. */
public class TransportException(public val kind: NetworkErrorKind, cause: Throwable? = null) :
    Exception("Transport failure: $kind", cause)
