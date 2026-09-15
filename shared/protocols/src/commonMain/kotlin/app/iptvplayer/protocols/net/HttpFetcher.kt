package app.iptvplayer.protocols.net

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.net.RedirectCheck
import app.iptvplayer.domain.net.UrlCheck
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.net.UrlFlag
import app.iptvplayer.domain.net.UrlPolicy
import app.iptvplayer.domain.ports.HttpMethod
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpResponse
import app.iptvplayer.domain.ports.HttpTimeouts
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.TransportException
import app.iptvplayer.domain.security.SensitiveUrl
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Request classes with timeouts, size caps and retry schedules (docs/IPTV_PROTOCOLS.md §4.4). */
public enum class RequestClass(public val timeouts: HttpTimeouts, public val maxBodyBytes: Long, public val backoff: List<Duration>) {
    /** Authentication, categories: small and interactive. */
    METADATA(HttpTimeouts(10.seconds, 20.seconds, 30.seconds), 16L * MIB, listOf(1.seconds, 3.seconds)),

    /** Full stream lists and guides: large, progress-based. */
    LARGE_LIST(HttpTimeouts(10.seconds, 30.seconds, 15.minutes), 256L * MIB, listOf(2.seconds)),

    /** On-demand detail such as series info. */
    LAZY_INFO(HttpTimeouts(10.seconds, 15.seconds, 20.seconds), 16L * MIB, listOf(1.seconds)),
    ;

    public val maxRetries: Int get() = backoff.size
}

private const val MIB: Long = 1024L * 1024L

public sealed interface FetchResult {
    /** A 2xx or 304 response. The caller owns [response] and must close its body. */
    public class Success(public val response: HttpResponse, public val flags: Set<UrlFlag>, public val attempts: Int) : FetchResult

    public data class Failure(public val error: DomainError, public val status: Int?, public val attempts: Int) : FetchResult
}

/**
 * Policy layer over the native [HttpTransport]: URL policy on every hop, manual redirects (no HTTPS downgrade), retries
 * only for transient failures, `Retry-After` honoured with a cap. Never logs or returns URLs in errors.
 */
public class HttpFetcher(
    private val transport: HttpTransport,
    private val jitter: () -> Double = { Random.nextDouble() },
    /** Waits between attempts; injectable so tests can assert the schedule without experimental test APIs. */
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
) {
    public suspend fun get(
        url: SensitiveUrl,
        requestClass: RequestClass,
        context: UrlContext,
        headers: Map<String, String> = emptyMap(),
    ): FetchResult {
        val initial = UrlPolicy.check(url.unsafeRawValue(), context)
        if (initial is UrlCheck.Rejected) return FetchResult.Failure(DomainError.Validation(ValidationFailure.URL_REJECTED), null, 0)
        var attempt = 0
        while (true) {
            attempt++
            val outcome = attemptOnce(url, (initial as UrlCheck.Allowed).flags, requestClass, context, headers, attempt)
            val retryAfter = outcome.retryDelay
            if (retryAfter == null || attempt > requestClass.maxRetries) return outcome.result
            sleep(retryAfter)
        }
    }

    private class Attempt(val result: FetchResult, val retryDelay: Duration?)

    private suspend fun attemptOnce(
        url: SensitiveUrl,
        initialFlags: Set<UrlFlag>,
        requestClass: RequestClass,
        context: UrlContext,
        headers: Map<String, String>,
        attempt: Int,
    ): Attempt {
        var current = url
        var flags = initialFlags
        var redirects = 0
        while (true) {
            val request =
                HttpRequest(HttpMethod.GET, current, headers, timeouts = requestClass.timeouts, maxBodyBytes = requestClass.maxBodyBytes)
            val response = try {
                transport.execute(request)
            } catch (e: TransportException) {
                val retryable = e.kind in RETRYABLE_NETWORK && attempt <= requestClass.maxRetries
                return Attempt(
                    FetchResult.Failure(DomainError.Network(e.kind), null, attempt),
                    if (retryable) backoff(requestClass, attempt) else null,
                )
            }
            val status = response.status
            when {
                status in 200..299 || status == 304 -> return Attempt(FetchResult.Success(response, flags, attempt), null)
                status in 300..399 -> {
                    response.body.close()
                    val location = header(response, "Location")
                        ?: return Attempt(FetchResult.Failure(DomainError.Http(status), status, attempt), null)
                    val base = UrlPolicy.check(current.unsafeRawValue(), context) as? UrlCheck.Allowed
                        ?: return Attempt(
                            FetchResult.Failure(DomainError.Validation(ValidationFailure.URL_REJECTED), status, attempt),
                            null,
                        )
                    when (val redirect = UrlPolicy.checkRedirect(base.url, location, redirects, context)) {
                        is RedirectCheck.Rejected ->
                            return Attempt(
                                FetchResult.Failure(DomainError.Validation(ValidationFailure.URL_REJECTED), status, attempt),
                                null,
                            )
                        is RedirectCheck.Allowed -> {
                            current = SensitiveUrl.of(redirect.url.toUrlString())
                            flags = redirect.flags
                            redirects++
                        }
                    }
                }
                else -> {
                    response.body.close()
                    val retryable =
                        (status == 408 || status == 429 || status in 500..599) && status != 501 && attempt <= requestClass.maxRetries
                    val wait = if (retryable) retryAfter(response) ?: backoff(requestClass, attempt) else null
                    return Attempt(FetchResult.Failure(DomainError.Http(status), status, attempt), wait)
                }
            }
        }
    }

    private fun backoff(requestClass: RequestClass, attempt: Int): Duration {
        val base = requestClass.backoff[(attempt - 1).coerceIn(0, requestClass.backoff.lastIndex)]
        return base + base * (0.25 * jitter().coerceIn(0.0, 1.0))
    }

    private fun retryAfter(response: HttpResponse): Duration? =
        header(response, "Retry-After")?.trim()?.toLongOrNull()?.coerceIn(0, MAX_RETRY_AFTER_SECONDS)?.seconds

    private fun header(response: HttpResponse, name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private companion object {
        const val MAX_RETRY_AFTER_SECONDS = 30L
        val RETRYABLE_NETWORK = setOf(NetworkErrorKind.TIMEOUT, NetworkErrorKind.CONNECTION_RESET, NetworkErrorKind.CONNECTION_REFUSED)
    }
}
