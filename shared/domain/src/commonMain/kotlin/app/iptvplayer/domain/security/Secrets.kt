package app.iptvplayer.domain.security

/**
 * A secret value that cannot leak through `toString()`, string templates or data-class printing.
 * Read it only where it must cross into a platform API, via [unsafeValue].
 */
public class Secret<T : Any>(private val value: T) {
    public fun unsafeValue(): T = value

    override fun toString(): String = "Secret(${Redactor.MARK})"

    override fun equals(other: Any?): Boolean = other is Secret<*> && other.value == value

    override fun hashCode(): Int = value.hashCode()
}

/**
 * A URL that may contain credentials or tokens (docs/SECURITY.md classes S1/S2). Printing it yields only the origin
 * (`scheme://host[:port]/‹redacted›`): providers put credentials in arbitrary query parameters and path segments, so no
 * pattern-based redaction is trusted for accidental printing. [redacted] gives the structural shape for diagnostics
 * exports; the raw value is available only through [unsafeRawValue] for transports and players.
 */
public class SensitiveUrl private constructor(private val raw: String) {
    public fun unsafeRawValue(): String = raw

    public fun redacted(redactor: Redactor = Redactor.STRUCTURAL): String = redactor.redactUrl(raw)

    override fun toString(): String = Redactor.origin(raw)

    override fun equals(other: Any?): Boolean = other is SensitiveUrl && other.raw == raw

    override fun hashCode(): Int = raw.hashCode()

    public companion object {
        public fun of(raw: String): SensitiveUrl = SensitiveUrl(raw)
    }
}

/** Secrets for one provider, as held by the platform secret store. Never persisted outside it. */
public class SecretBundle(
    public val username: Secret<String>? = null,
    public val password: Secret<String>? = null,
    public val secretUrl: SensitiveUrl? = null,
    public val secretHeaders: Map<String, Secret<String>> = emptyMap(),
) {
    /** Every raw secret value in this bundle, for configuring a [Redactor]. */
    public fun rawValues(): List<String> = buildList {
        username?.let { add(it.unsafeValue()) }
        password?.let { add(it.unsafeValue()) }
        secretUrl?.let { add(it.unsafeRawValue()) }
        secretHeaders.values.forEach { add(it.unsafeValue()) }
    }

    override fun toString(): String = "SecretBundle(${Redactor.MARK})"
}
