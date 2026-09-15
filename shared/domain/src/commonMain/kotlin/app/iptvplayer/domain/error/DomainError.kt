package app.iptvplayer.domain.error

public enum class NetworkErrorKind { OFFLINE, DNS, TLS, TIMEOUT, CONNECTION_REFUSED, CONNECTION_RESET, UNKNOWN }

public enum class AuthFailure {
    INVALID_CREDENTIALS,
    ACCOUNT_EXPIRED,
    ACCOUNT_BANNED,
    ACCOUNT_DISABLED,
    MISSING_CREDENTIALS,
    CONNECTION_LIMIT,
}

public enum class ValidationFailure {
    SOURCE_IS_HLS_PLAYLIST,
    UNEXPECTED_CONTENT_TYPE_HTML,
    UNRECOGNIZED_FORMAT,
    EMPTY_RESPONSE,
    URL_REJECTED,
    SERVER_HOST_MISMATCH,
}

public enum class LimitKind {
    DOWNLOAD_SIZE,
    DECOMPRESSED_SIZE,
    COMPRESSION_RATIO,
    LINE_LENGTH,
    TEXT_LENGTH,
    DEPTH,
    ATTRIBUTE_COUNT,
    RECORD_COUNT,
}

/**
 * Errors shown to users map to a stable [code], a message key and a retryable flag. No raw exception text,
 * URL or provider response ever appears in a DomainError (docs/ARCHITECTURE.md §9).
 */
public sealed class DomainError {
    public abstract val code: String
    public abstract val retryable: Boolean
    public val messageKey: String get() = "error." + code.lowercase()

    public data class Network(public val kind: NetworkErrorKind) : DomainError() {
        override val code: String get() = "NET_$kind"
        override val retryable: Boolean get() = kind != NetworkErrorKind.TLS
    }

    public data class Http(public val status: Int) : DomainError() {
        override val code: String get() = "HTTP_$status"
        override val retryable: Boolean get() = status == 408 || status == 429 || status >= 500
    }

    public data class Auth(public val reason: AuthFailure) : DomainError() {
        override val code: String get() = "AUTH_$reason"
        override val retryable: Boolean get() = false
    }

    public data class Validation(public val reason: ValidationFailure) : DomainError() {
        override val code: String get() = "VALIDATION_$reason"
        override val retryable: Boolean get() = false
    }

    /** A unit could not be parsed at all; per-record problems are ImportDiagnostics, not errors. */
    public data class Parse(public val diagnosticCode: String) : DomainError() {
        override val code: String get() = "PARSE_$diagnosticCode"
        override val retryable: Boolean get() = false
    }

    public data class Limit(public val limit: LimitKind) : DomainError() {
        override val code: String get() = "LIMIT_$limit"
        override val retryable: Boolean get() = false
    }

    public data class Storage(public val detailCode: String) : DomainError() {
        override val code: String get() = "STORAGE_$detailCode"
        override val retryable: Boolean get() = true
    }

    public data object Cancelled : DomainError() {
        override val code: String get() = "CANCELLED"
        override val retryable: Boolean get() = false
    }

    public data class Unsupported(public val capability: String) : DomainError() {
        override val code: String get() = "UNSUPPORTED_$capability"
        override val retryable: Boolean get() = false
    }
}
