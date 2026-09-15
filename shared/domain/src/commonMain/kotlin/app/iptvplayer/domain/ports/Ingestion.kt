package app.iptvplayer.domain.ports

import app.iptvplayer.domain.capability.Capabilities
import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.Playlist
import app.iptvplayer.domain.model.ProtocolId
import app.iptvplayer.domain.model.Provider
import app.iptvplayer.domain.model.ProviderAccount
import app.iptvplayer.domain.security.SecretBundle

// Ingestion pipeline contracts (docs/IPTV_PROTOCOLS.md §1, §6). Provisional: refined when the first real adapter
// (M3U, Phase 2) exists, so the contract is shaped by an implementation rather than guessed.

public enum class DiagnosticSeverity { INFO, WARNING, ERROR }

public data class DiagnosticLocation(public val line: Long? = null, public val path: String? = null)

/**
 * Per-record import diagnostic. [unit] is null for source-level findings made before content is classified (e.g. M3U
 * parsing). [sampleRedacted] must already have passed the Redactor.
 */
public data class ImportDiagnostic(
    public val unit: ImportUnit?,
    public val severity: DiagnosticSeverity,
    public val code: String,
    public val location: DiagnosticLocation?,
    public val message: String,
    public val sampleRedacted: String?,
)

public fun interface DiagnosticSink {
    public fun report(diagnostic: ImportDiagnostic)
}

/** Keeps the first [maxKept] diagnostics and counts every diagnostic per code (docs/DOMAIN_MODEL.md §8). */
public class CollectingDiagnosticSink(private val maxKept: Int = 1_000) : DiagnosticSink {
    private val kept = ArrayList<ImportDiagnostic>()
    private val counts = LinkedHashMap<String, Int>()

    public val diagnostics: List<ImportDiagnostic> get() = kept
    public val countsByCode: Map<String, Int> get() = counts
    public val total: Int get() = counts.values.sum()

    override fun report(diagnostic: ImportDiagnostic) {
        counts[diagnostic.code] = (counts[diagnostic.code] ?: 0) + 1
        if (kept.size < maxKept) kept.add(diagnostic)
    }
}

public fun interface RecordSink<in R> {
    public fun accept(record: R)
}

/** Resource limits for untrusted input (docs/SECURITY.md §6). */
public data class ParseLimits(
    public val maxBytes: Long,
    public val maxDecompressedBytes: Long,
    public val maxCompressionRatio: Int?,
    public val maxLineOrTokenBytes: Int,
    public val maxDepth: Int,
    public val maxAttributes: Int,
    public val maxRecords: Long,
    public val maxDiagnostics: Int = 1_000,
) {
    public companion object {
        private const val MIB: Long = 1024L * 1024L

        public val M3U: ParseLimits = ParseLimits(
            maxBytes = 256 * MIB,
            maxDecompressedBytes = 256 * MIB,
            maxCompressionRatio = null,
            maxLineOrTokenBytes = 64 * 1024,
            maxDepth = 1,
            maxAttributes = 64,
            maxRecords = 1_000_000,
        )

        public val XMLTV: ParseLimits = ParseLimits(
            maxBytes = 256 * MIB,
            maxDecompressedBytes = 2048 * MIB,
            maxCompressionRatio = 100,
            maxLineOrTokenBytes = 64 * 1024,
            maxDepth = 16,
            maxAttributes = 32,
            maxRecords = 5_000_000,
        )

        public val XTREAM_JSON: ParseLimits = ParseLimits(
            maxBytes = 256 * MIB,
            maxDecompressedBytes = 512 * MIB,
            maxCompressionRatio = 100,
            maxLineOrTokenBytes = 1024 * 1024,
            maxDepth = 32,
            maxAttributes = 256,
            maxRecords = 1_000_000,
        )
    }
}

/** How a parse ended. Records emitted before a stop remain valid. */
public sealed interface ParseOutcome {
    public data object Completed : ParseOutcome

    public data class Stopped(public val limit: LimitKind) : ParseOutcome

    public data class Rejected(public val failure: ValidationFailure) : ParseOutcome
}

public interface StreamingParser<R> {
    /**
     * Push-based and bounded: never materializes the whole document and never throws on malformed input.
     * Malformed records go to [diagnostics].
     */
    public fun parse(input: ByteSource, limits: ParseLimits, sink: RecordSink<R>, diagnostics: DiagnosticSink): ParseOutcome
}

public class NormalizeContext(
    public val unit: ImportUnit,
    public val playlistId: PlaylistId?,
    public val epgSourceId: EpgSourceId?,
    public val diagnostics: DiagnosticSink,
)

public fun interface Normalizer<in R, out T : Any> {
    /** Returns null (after reporting a diagnostic) when the record cannot become a valid domain entity. */
    public fun normalize(record: R, context: NormalizeContext): T?
}

public class SourceContext(public val provider: Provider, public val playlist: Playlist?, public val secrets: SecretBundle?)

public class DiscoveryResult(public val capabilities: Capabilities, public val account: ProviderAccount?)

public class ImportPlan(public val units: List<ImportUnit>)

public interface SourceAdapter {
    public val protocol: ProtocolId

    /** Authenticates where applicable and discovers capabilities. */
    public suspend fun discover(context: SourceContext): DiscoveryResult

    public fun plan(context: SourceContext, discovery: DiscoveryResult): ImportPlan
}
