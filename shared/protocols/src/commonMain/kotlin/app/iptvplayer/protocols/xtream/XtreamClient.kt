package app.iptvplayer.protocols.xtream

import app.iptvplayer.domain.capability.Capabilities
import app.iptvplayer.domain.capability.StreamFormat
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.AccountStatus
import app.iptvplayer.domain.model.ContentKind
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.Program
import app.iptvplayer.domain.model.ProviderAccount
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.CollectingDiagnosticSink
import app.iptvplayer.domain.ports.ImportDiagnostic
import app.iptvplayer.domain.ports.ParseLimits
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.content.ImportCounts
import app.iptvplayer.protocols.io.PrefixReplaySource
import app.iptvplayer.protocols.io.readPrefix
import app.iptvplayer.protocols.json.JsonArrayStreamer
import app.iptvplayer.protocols.json.LenientObject
import app.iptvplayer.protocols.net.FetchResult
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.net.RequestClass
import app.iptvplayer.protocols.sniff.ContentSniffer
import app.iptvplayer.protocols.sniff.SniffedFormat
import kotlinx.serialization.json.JsonElement

public sealed interface XtreamDiscovery {
    public class Authenticated(
        public val account: ProviderAccount,
        public val capabilities: Capabilities,
        /** Host reported by `server_info.url`; surfaced to the user when it differs, never adopted automatically. */
        public val reportedHost: String?,
        public val hostMismatch: Boolean,
        /** The panel reports an HTTPS port while the user entered http: offer, never switch automatically. */
        public val httpsAvailable: Boolean,
        public val epgTemplate: UrlTemplate,
        public val diagnostics: List<ImportDiagnostic>,
    ) : XtreamDiscovery

    public data class Failed(public val error: DomainError) : XtreamDiscovery
}

public class XtreamUnitResult(
    public val unit: ImportUnit,
    public val status: ImportStatus,
    public val counts: ImportCounts,
    public val error: DomainError?,
    public val diagnostics: List<ImportDiagnostic>,
    public val diagnosticCounts: Map<String, Int>,
)

public class XtreamImportResult(public val units: List<XtreamUnitResult>) {
    /** PUBLISHED when every unit published, FAILED when none did, otherwise PARTIAL (§4.5). */
    public val playlistStatus: ImportStatus
        get() = when {
            units.all { it.status == ImportStatus.PUBLISHED } -> ImportStatus.PUBLISHED
            units.none { it.status == ImportStatus.PUBLISHED } -> ImportStatus.FAILED
            else -> ImportStatus.PARTIAL
        }
}

/**
 * Xtream Codes discovery and import over [HttpFetcher] (docs/IPTV_PROTOCOLS.md §4). Items are emitted as they are
 * normalized; a unit that ends FAILED must have its emitted items discarded by the caller so the previous snapshot stays
 * intact. Truncated or broken list JSON fails the unit: publishing part of a list would delete the rest from the snapshot.
 */
public class XtreamClient(
    private val fetcher: HttpFetcher,
    private val clock: Clock,
    private val limits: ParseLimits = ParseLimits.XTREAM_JSON,
) {
    public suspend fun discover(endpoint: XtreamEndpoint, credentials: XtreamCredentials): XtreamDiscovery {
        val diagnostics = CollectingDiagnosticSink()
        val reporter = XtreamReporter(null, diagnostics)
        val document = when (val body = fetchDocument(endpoint, credentials, null, emptyList(), RequestClass.METADATA)) {
            is Body.Document -> body.element
            is Body.Error -> return XtreamDiscovery.Failed(body.error)
        }
        val root =
            LenientObject.of(document, reporter::mismatch)
                ?: return XtreamDiscovery.Failed(DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT))
        val user = root.obj("user_info") ?: return XtreamDiscovery.Failed(DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT))
        authFailure(user)?.let { return XtreamDiscovery.Failed(DomainError.Auth(it)) }

        val server = root.obj("server_info")
        val expiresAt = user.epochSeconds("exp_date")
        if (expiresAt != null &&
            expiresAt < clock.now()
        ) {
            reporter.warning(XtreamDiagnosticCodes.EXPIRY_IN_PAST, "Account reports Active but its expiry date has passed")
        }
        val maxConnections = user.int("max_connections")
        val activeConnections = user.int("active_cons")
        if (maxConnections != null && activeConnections != null && activeConnections >= maxConnections) {
            reporter.warning(XtreamDiagnosticCodes.CONNECTION_LIMIT_REACHED, "All allowed connections are currently in use")
        }
        val formats = user.stringList("allowed_output_formats").mapNotNull { formatOf(it) }.toSet()
        val account = ProviderAccount(
            status = AccountStatus.ACTIVE,
            expiresAt = expiresAt,
            isTrial = user.bool("is_trial"),
            maxConnections = maxConnections,
            activeConnections = activeConnections,
            allowedOutputFormats = formats,
            serverTimezone = server?.string("timezone"),
        )
        val reportedHost = server?.string("url")?.substringBefore(':')?.lowercase()
        val hostMismatch = reportedHost != null && reportedHost != endpoint.host
        if (hostMismatch) {
            reporter.warning(
                XtreamDiagnosticCodes.SERVER_HOST_MISMATCH,
                "Panel reports a different server host; the entered host is kept",
            )
        }
        val httpsAvailable =
            endpoint.scheme == "http" && (server?.string("server_protocol") == "https" || server?.int("https_port") != null)
        if (httpsAvailable) reporter.info(XtreamDiagnosticCodes.HTTPS_AVAILABLE, "Panel reports HTTPS support")

        val capabilities = Capabilities.UNDISCOVERED.copy(
            liveTv = probe(endpoint, credentials, "get_live_categories", reporter),
            movies = probe(endpoint, credentials, "get_vod_categories", reporter),
            series = probe(endpoint, credentials, "get_series_categories", reporter),
            maxConnections = maxConnections,
            streamFormats = formats,
        )
        return XtreamDiscovery.Authenticated(
            account,
            capabilities,
            reportedHost,
            hostMismatch,
            httpsAvailable,
            endpoint.xmltvTemplate,
            diagnostics.diagnostics,
        )
    }

    public suspend fun importAll(
        units: List<ImportUnit>,
        endpoint: XtreamEndpoint,
        credentials: XtreamCredentials,
        playlistId: PlaylistId,
        account: ProviderAccount?,
        emit: (ImportUnit, ContentItem) -> Unit,
    ): XtreamImportResult = XtreamImportResult(
        units.map { unit ->
            importUnit(unit, endpoint, credentials, playlistId, account) {
                emit(unit, it)
            }
        },
    )

    public suspend fun importUnit(
        unit: ImportUnit,
        endpoint: XtreamEndpoint,
        credentials: XtreamCredentials,
        playlistId: PlaylistId,
        account: ProviderAccount?,
        emit: (ContentItem) -> Unit,
    ): XtreamUnitResult {
        val diagnostics = CollectingDiagnosticSink(limits.maxDiagnostics)
        val reporter = XtreamReporter(unit, diagnostics)
        val normalizer = XtreamNormalizer(playlistId, reporter, credentials, account?.allowedOutputFormats.orEmpty())
        var counts = ImportCounts()
        val (categoryAction, listAction, kind) = when (unit) {
            ImportUnit.LIVE -> Triple("get_live_categories", "get_live_streams", ContentKind.LIVE)
            ImportUnit.MOVIES -> Triple("get_vod_categories", "get_vod_streams", ContentKind.MOVIE)
            ImportUnit.SERIES -> Triple("get_series_categories", "get_series", ContentKind.SERIES)
            ImportUnit.EPG -> return result(unit, ImportStatus.FAILED, counts, DomainError.Unsupported("XTREAM_EPG_VIA_XMLTV"), diagnostics)
        }

        var order = 0
        val categoryError = streamList(endpoint, credentials, categoryAction, RequestClass.METADATA, reporter) { element ->
            normalizer.category(element, kind, order++)?.let {
                emit(it)
                counts = counts.copy(groups = counts.groups + 1)
            }
        }
        if (categoryError != null) return result(unit, ImportStatus.FAILED, counts, categoryError, diagnostics)

        val listError = streamList(endpoint, credentials, listAction, RequestClass.LARGE_LIST, reporter) { element ->
            counts = counts.copy(entries = counts.entries + 1)
            val item: ContentItem? = when (unit) {
                ImportUnit.LIVE -> normalizer.liveStream(element)?.also { counts = counts.copy(channels = counts.channels + 1) }
                ImportUnit.MOVIES -> normalizer.vodStream(element)?.also { counts = counts.copy(movies = counts.movies + 1) }
                else -> normalizer.series(element)?.also { counts = counts.copy(series = counts.series + 1) }
            }
            if (item == null) counts = counts.copy(rejectedEntries = counts.rejectedEntries + 1) else emit(item)
        }
        return if (listError != null) {
            result(unit, ImportStatus.FAILED, counts, listError, diagnostics)
        } else {
            result(unit, ImportStatus.PUBLISHED, counts, null, diagnostics)
        }
    }

    /** Lazily loads seasons and episodes for one series (`get_series_info`). */
    public suspend fun seriesInfo(
        endpoint: XtreamEndpoint,
        credentials: XtreamCredentials,
        playlistId: PlaylistId,
        providerSeriesId: String,
        account: ProviderAccount?,
    ): Pair<List<ContentItem>, DomainError?> {
        val diagnostics = CollectingDiagnosticSink()
        val normalizer =
            XtreamNormalizer(
                playlistId,
                XtreamReporter(ImportUnit.SERIES, diagnostics),
                credentials,
                account?.allowedOutputFormats.orEmpty(),
            )
        return when (
            val body = fetchDocument(
                endpoint,
                credentials,
                "get_series_info",
                listOf("series_id" to providerSeriesId),
                RequestClass.LAZY_INFO,
            )
        ) {
            is Body.Document -> normalizer.seriesInfo(body.element, normalizer.seriesId(providerSeriesId)) to null
            is Body.Error -> emptyList<ContentItem>() to body.error
        }
    }

    /** Now/next fallback for channels without XMLTV data (`get_short_epg`). */
    public suspend fun shortEpg(
        endpoint: XtreamEndpoint,
        credentials: XtreamCredentials,
        streamId: String,
        key: EpgChannelKey,
        limit: Int = 4,
    ): Pair<List<Program>, DomainError?> {
        val normalizer =
            XtreamNormalizer(PlaylistId("short-epg"), XtreamReporter(ImportUnit.EPG, CollectingDiagnosticSink()), credentials, emptySet())
        val parameters = listOf("stream_id" to streamId, "limit" to limit.toString())
        return when (val body = fetchDocument(endpoint, credentials, "get_short_epg", parameters, RequestClass.LAZY_INFO)) {
            is Body.Document -> normalizer.shortEpg(body.element, key) to null
            is Body.Error -> emptyList<Program>() to body.error
        }
    }

    private sealed interface Body {
        data class Document(val element: JsonElement) : Body

        data class Error(val error: DomainError) : Body
    }

    private suspend fun fetchDocument(
        endpoint: XtreamEndpoint,
        credentials: XtreamCredentials,
        action: String?,
        parameters: List<Pair<String, String>>,
        requestClass: RequestClass,
    ): Body {
        val fetched = fetcher.get(endpoint.apiUrl(credentials, action, parameters), requestClass, UrlContext.API)
        val response = when (fetched) {
            is FetchResult.Failure -> return Body.Error(httpError(fetched))
            is FetchResult.Success -> fetched.response
        }
        try {
            val prefix = readPrefix(response.body, ContentSniffer.SNIFF_BYTES)
            validation(ContentSniffer.sniff(prefix))?.let { return Body.Error(it) }
            val streamer = JsonArrayStreamer(limits.maxBytes, DOCUMENT_LIMIT, limits.maxDepth, limits.maxRecords)
            val element = streamer.parseDocument(PrefixReplaySource(prefix, response.body))
                ?: return Body.Error(DomainError.Parse(PARSE_INVALID_JSON))
            return Body.Document(element)
        } finally {
            response.body.close()
        }
    }

    /** Streams a JSON array list endpoint into [onElement]; returns the unit-failing error, or null on success. */
    private suspend fun streamList(
        endpoint: XtreamEndpoint,
        credentials: XtreamCredentials,
        action: String,
        requestClass: RequestClass,
        reporter: XtreamReporter,
        onElement: (JsonElement) -> Unit,
    ): DomainError? {
        val response = when (val fetched = fetcher.get(endpoint.apiUrl(credentials, action), requestClass, UrlContext.API)) {
            is FetchResult.Failure -> return httpError(fetched)
            is FetchResult.Success -> fetched.response
        }
        try {
            val prefix = readPrefix(response.body, ContentSniffer.SNIFF_BYTES)
            validation(ContentSniffer.sniff(prefix))?.let { return it }
            val streamer = JsonArrayStreamer(limits.maxBytes, limits.maxLineOrTokenBytes, limits.maxDepth, limits.maxRecords)
            var index = 0L
            val outcome = streamer.stream(PrefixReplaySource(prefix, response.body)) { element ->
                reporter.index = index++
                onElement(element)
            }
            reporter.index = null
            return when (outcome) {
                is JsonArrayStreamer.Outcome.Completed, JsonArrayStreamer.Outcome.Empty -> null
                is JsonArrayStreamer.Outcome.Malformed -> DomainError.Parse(PARSE_INVALID_JSON)
                is JsonArrayStreamer.Outcome.Stopped -> DomainError.Limit(outcome.limit)
                is JsonArrayStreamer.Outcome.NotAnArray -> {
                    val user = LenientObject.of(outcome.objectElement, reporter::mismatch)?.obj("user_info")
                    user?.let { authFailure(it) }?.let { DomainError.Auth(it) }
                        ?: DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT)
                }
            }
        } finally {
            response.body.close()
        }
    }

    private suspend fun probe(endpoint: XtreamEndpoint, credentials: XtreamCredentials, action: String, reporter: XtreamReporter): Support {
        var count = 0
        val error = streamList(endpoint, credentials, action, RequestClass.METADATA, reporter) { count++ }
        if (error != null) {
            reporter.info(XtreamDiagnosticCodes.CAPABILITY_PROBE_FAILED, "Capability probe '$action' failed: ${error.code}")
            return Support.UNKNOWN
        }
        return if (count > 0) Support.SUPPORTED else Support.UNSUPPORTED
    }

    private fun authFailure(user: LenientObject): AuthFailure? {
        if (user.bool("auth") != true) return AuthFailure.INVALID_CREDENTIALS
        return when (user.string("status")?.lowercase()) {
            null, "active" -> null
            "expired" -> AuthFailure.ACCOUNT_EXPIRED
            "banned" -> AuthFailure.ACCOUNT_BANNED
            "disabled" -> AuthFailure.ACCOUNT_DISABLED
            else -> null
        }
    }

    private fun validation(format: SniffedFormat): DomainError? = when (format) {
        SniffedFormat.JSON -> null
        SniffedFormat.HTML -> DomainError.Validation(ValidationFailure.UNEXPECTED_CONTENT_TYPE_HTML)
        SniffedFormat.EMPTY -> DomainError.Validation(ValidationFailure.EMPTY_RESPONSE)
        else -> DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT)
    }

    private fun httpError(failure: FetchResult.Failure): DomainError = when (failure.status) {
        401, 403 -> DomainError.Auth(AuthFailure.INVALID_CREDENTIALS)
        else -> failure.error
    }

    private fun result(
        unit: ImportUnit,
        status: ImportStatus,
        counts: ImportCounts,
        error: DomainError?,
        diagnostics: CollectingDiagnosticSink,
    ) = XtreamUnitResult(unit, status, counts, error, diagnostics.diagnostics, diagnostics.countsByCode)

    private fun formatOf(value: String): StreamFormat? = when (value.trim().lowercase()) {
        "m3u8", "hls" -> StreamFormat.HLS
        "ts", "mpegts" -> StreamFormat.MPEG_TS
        "rtmp" -> StreamFormat.RTMP
        else -> null
    }

    private companion object {
        const val PARSE_INVALID_JSON = "XTREAM_INVALID_JSON"
        const val DOCUMENT_LIMIT = 16 * 1024 * 1024
    }
}
