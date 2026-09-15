package app.iptvplayer.ingestion

import app.iptvplayer.domain.capability.Capabilities
import app.iptvplayer.domain.capability.PlatformCapabilities
import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.model.EpgChannel
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.PreferredStreamFormat
import app.iptvplayer.domain.model.ProtocolId
import app.iptvplayer.domain.model.Provider
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.net.ParsedUrl
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.ParseOutcome
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.epg.EpgMatchCandidate
import app.iptvplayer.epg.EpgMatcher
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.content.SourceCredentials
import app.iptvplayer.protocols.m3u.M3uImporter
import app.iptvplayer.protocols.m3u.XtreamM3uDetector
import app.iptvplayer.protocols.media.MediaSourceResolver
import app.iptvplayer.protocols.media.ResolveResult
import app.iptvplayer.protocols.net.FetchResult
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.net.RequestClass
import app.iptvplayer.protocols.xmltv.XmltvImportOptions
import app.iptvplayer.protocols.xmltv.XmltvImporter
import app.iptvplayer.protocols.xtream.XtreamClient
import app.iptvplayer.protocols.xtream.XtreamCredentials
import app.iptvplayer.protocols.xtream.XtreamDiscovery
import app.iptvplayer.protocols.xtream.XtreamEndpoint
import app.iptvplayer.storage.ChannelEpgLinkRow
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.storage.ProgramRow
import app.iptvplayer.storage.SourceRecord
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** Why adding a source failed, in terms the onboarding screen can explain (docs/DESIGN_SYSTEM.md §5). */
public enum class AddSourceFailure {
    INVALID_URL,
    INVALID_CREDENTIALS,
    ACCOUNT_EXPIRED,
    ACCOUNT_DISABLED,
    CONNECTION_FAILED,
    SERVER_ERROR,
    NOT_A_PLAYLIST,
}

public sealed interface AddSourceResult {
    public data class Added(public val playlistId: PlaylistId, public val live: UnitOutcome) : AddSourceResult

    public data class Rejected(public val reason: AddSourceFailure, public val error: DomainError?) : AddSourceResult
}

public data class UnitOutcome(
    public val unit: ImportUnit,
    public val status: ImportStatus,
    public val itemCount: Int,
    public val error: DomainError?,
    /** For guide imports: what the provider's XMLTV contained (numbers only; safe to log). */
    public val guide: GuideSummary? = null,
)

/**
 * What a guide import found, to tell an empty provider guide from one whose times fall outside the kept window. Stored as
 * the EPG unit's code when nothing was kept: [EMPTY] or [OUTSIDE_WINDOW].
 */
public data class GuideSummary(
    public val declaredChannels: Int,
    public val programmesRead: Int,
    public val programmesKept: Int,
    public val outsideWindow: Int,
    public val dropped: Int,
    public val linkedChannels: Int,
    /** True when the provider's `xmltv.php` was empty and this guide came from the link in its playlist header. */
    public val fromPlaylistHeader: Boolean = false,
    /** Outcome of looking for a guide link in the playlist header (codes only), when that was tried. */
    public val playlistHeader: String? = null,
) {
    public val code: String?
        get() = when {
            programmesKept > 0 -> null
            programmesRead == 0 -> EMPTY
            outsideWindow > 0 -> OUTSIDE_WINDOW
            else -> null
        }

    public companion object {
        public const val EMPTY: String = "EPG_EMPTY"
        public const val OUTSIDE_WINDOW: String = "EPG_OUTSIDE_WINDOW"
    }
}

/**
 * The import pipeline for user sources (docs/IPTV_PROTOCOLS.md §6, ARCHITECTURE.md §5): adding Xtream and M3U sources,
 * importing live channels into a new snapshot, importing and matching the guide, and resolving a channel to a playable
 * stream. Credentials go to [secrets] only (ADR-0015); the database stores templates with placeholders.
 */
public class SourceService(
    transport: HttpTransport,
    private val secrets: SecretStore,
    private val content: ContentStore,
    private val epg: EpgStore,
    private val clock: Clock,
    private val platform: PlatformCapabilities,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private val fetcher = HttpFetcher(transport)
    private val xtream = XtreamClient(fetcher, clock)
    private val shortGuide = ShortGuide(xtream, content, secrets, clock)

    /**
     * True when [url] is an Xtream Codes playlist link (`…/get.php?username=<user>&password=<password>`). Such a source can be added with
     * the Xtream API instead: live channels and the guide without downloading the whole playlist, which on big providers
     * lists every movie and episode (IPTV_PROTOCOLS.md §3.4). Never logs the URL.
     */
    public fun isXtreamPlaylistLink(url: String): Boolean = XtreamM3uDetector.detect(SensitiveUrl.of(url.trim())) != null

    /** Adds an Xtream Codes playlist link through the Xtream API ([isXtreamPlaylistLink]). */
    public suspend fun addXtreamFromPlaylistLink(name: String?, url: String): AddSourceResult {
        val link = XtreamM3uDetector.detect(SensitiveUrl.of(url.trim()))
            ?: return AddSourceResult.Rejected(AddSourceFailure.INVALID_URL, null)
        return addXtream(name, link.baseUrl, link.username.unsafeValue(), link.password.unsafeValue())
    }

    public suspend fun addXtream(name: String?, serverUrl: String, username: String, password: String): AddSourceResult {
        val endpoint = XtreamEndpoint.parse(serverUrl) ?: return AddSourceResult.Rejected(AddSourceFailure.INVALID_URL, null)
        if (username.isBlank() || password.isEmpty()) return AddSourceResult.Rejected(AddSourceFailure.INVALID_CREDENTIALS, null)
        val credentials = XtreamCredentials(Secret(username.trim()), Secret(password))
        val discovery = xtream.discover(endpoint, credentials)
        if (discovery is XtreamDiscovery.Failed) return AddSourceResult.Rejected(failureOf(discovery.error), discovery.error)
        discovery as XtreamDiscovery.Authenticated

        val playlistId = PlaylistId(newId())
        val providerId = ProviderId(newId())
        val ref = CredentialRef(newId())
        secrets.put(ref, SecretBundle(username = credentials.username, password = credentials.password))
        content.addSource(
            playlistId,
            providerId,
            name?.trim()?.ifEmpty { null } ?: endpoint.host,
            PlaylistType.XTREAM,
            endpoint.display,
            endpoint.template,
            ref,
            if (endpoint.scheme == "https") TransportSecurity.TLS else TransportSecurity.CLEARTEXT,
        )
        content.updateAccount(providerId, discovery.account)
        content.setEpgTemplate(playlistId, discovery.epgTemplate)
        return AddSourceResult.Added(playlistId, refreshLive(playlistId))
    }

    /**
     * Adds an M3U playlist URL. An Xtream-generated `get.php` URL keeps its credentials in the secret store and a template in
     * the database; any other URL is stored whole in the secret store, because playlist URLs often embed tokens.
     */
    public suspend fun addM3u(name: String?, url: String): AddSourceResult {
        val parsed = ParsedUrl.parse(url.trim())
        if (parsed == null || !parsed.hasAuthority || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return AddSourceResult.Rejected(AddSourceFailure.INVALID_URL, null)
        }
        val raw = SensitiveUrl.of(url.trim())
        val xtreamStyle = XtreamM3uDetector.detect(raw)
        val playlistId = PlaylistId(newId())
        val providerId = ProviderId(newId())
        val ref = CredentialRef(newId())
        val template = if (xtreamStyle != null) {
            UrlTemplate.fromUrl(raw.unsafeRawValue(), xtreamStyle.username.unsafeValue(), xtreamStyle.password.unsafeValue())
                ?: return AddSourceResult.Rejected(AddSourceFailure.INVALID_URL, null)
        } else {
            UrlTemplate(SECRET_URL_TEMPLATE)
        }
        secrets.put(
            ref,
            if (xtreamStyle !=
                null
            ) {
                SecretBundle(username = xtreamStyle.username, password = xtreamStyle.password)
            } else {
                SecretBundle(secretUrl = raw)
            },
        )
        content.addSource(
            playlistId,
            providerId,
            name?.trim()?.ifEmpty { null } ?: parsed.host,
            PlaylistType.M3U_URL,
            "${parsed.scheme}://${parsed.hostAndPort}",
            template,
            ref,
            if (parsed.scheme == "https") TransportSecurity.TLS else TransportSecurity.CLEARTEXT,
        )
        val live = refreshLive(playlistId)
        if (live.status == ImportStatus.FAILED) {
            deleteSource(playlistId)
            return AddSourceResult.Rejected(live.error?.let { failureOf(it) } ?: AddSourceFailure.NOT_A_PLAYLIST, live.error)
        }
        return AddSourceResult.Added(playlistId, live)
    }

    public suspend fun deleteSource(playlistId: PlaylistId) {
        val source = content.source(playlistId)
        content.deleteSource(playlistId)
        source?.credentialRef?.let { secrets.delete(it) }
        secrets.delete(guideLinkRef(playlistId))
    }

    /**
     * Uses [url] as the source's XMLTV guide instead of the provider's (REQUIREMENTS.md FR-SRC-004), for providers whose own
     * guide is empty. The link is kept in the secret store (guide links often carry tokens); the database only records that
     * a custom link is set. Returns null when saved, or why it was refused. Refresh the guide afterwards.
     */
    public suspend fun setGuideLink(playlistId: PlaylistId, url: String): AddSourceFailure? {
        val parsed = ParsedUrl.parse(url.trim())
        if (parsed == null || !parsed.hasAuthority || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return AddSourceFailure.INVALID_URL
        }
        content.source(playlistId) ?: return AddSourceFailure.INVALID_URL
        secrets.put(guideLinkRef(playlistId), SecretBundle(secretUrl = SensitiveUrl.of(url.trim())))
        content.setEpgTemplate(playlistId, UrlTemplate(GUIDE_LINK_TEMPLATE))
        return null
    }

    /** Returns to the provider's own guide: `xmltv.php` for Xtream; for M3U the playlist's `url-tvg` at the next refresh. */
    public suspend fun clearGuideLink(playlistId: PlaylistId) {
        val source = content.source(playlistId) ?: return
        secrets.delete(guideLinkRef(playlistId))
        val providerGuide = if (source.type == PlaylistType.XTREAM) {
            source.sourceTemplate?.let { XtreamEndpoint.parse(it.template) }?.xmltvTemplate
        } else {
            null
        }
        content.setEpgTemplate(playlistId, providerGuide)
    }

    /** True when the source uses a guide link set with [setGuideLink]. */
    public fun hasGuideLink(source: SourceRecord): Boolean = source.epgTemplate?.template == GUIDE_LINK_TEMPLATE

    private fun guideLinkRef(playlistId: PlaylistId) = CredentialRef("guide-link:${playlistId.value}")

    /** Imports live channels into a new snapshot and publishes it; on failure the previous channels stay visible. */
    public suspend fun refreshLive(playlistId: PlaylistId): UnitOutcome {
        val source =
            content.source(playlistId) ?: return UnitOutcome(ImportUnit.LIVE, ImportStatus.FAILED, 0, DomainError.Storage("SOURCE_MISSING"))
        val bundle = source.credentialRef?.let { secrets.get(it) }
        content.markUnit(playlistId, ImportUnit.LIVE, ImportStatus.RUNNING)
        val writer = content.beginLiveSnapshot(playlistId)
        val sensitiveHeaders = ArrayList<ContentItem.SensitiveHeader>()
        var epgHint: UrlTemplate? = null
        val emit: (ContentItem) -> Unit = { item ->
            when (item) {
                is ContentItem.Group -> if (item.group.contentKind == app.iptvplayer.domain.model.ContentKind.LIVE) writer.group(item.group)
                is ContentItem.ChannelItem -> writer.channel(item.channel, item.mediaSource, item.logo?.url)
                is ContentItem.GroupMembership -> writer.membership(item.channelId, item.groupId.value)
                is ContentItem.EpgHint -> if (epgHint == null) epgHint = item.url
                is ContentItem.SensitiveHeader -> sensitiveHeaders += item
                else -> Unit
            }
        }
        val outcome = try {
            when (source.type) {
                PlaylistType.XTREAM -> importXtreamLive(source, bundle, emit)
                else -> importM3u(source, bundle, emit)
            }
        } catch (e: Exception) {
            writer.discard()
            content.markUnit(playlistId, ImportUnit.LIVE, ImportStatus.FAILED, "EXCEPTION")
            throw e
        }
        if (outcome.status == ImportStatus.FAILED) {
            writer.discard()
            content.markUnit(playlistId, ImportUnit.LIVE, ImportStatus.FAILED, outcome.error?.code)
            return outcome
        }
        for (header in sensitiveHeaders) secrets.put(header.ref, SecretBundle(secretHeaders = mapOf(header.name to header.value)))
        writer.publish()
        if (source.epgTemplate == null) epgHint?.let { content.setEpgTemplate(playlistId, it) }
        return outcome.copy(itemCount = writer.channelCount)
    }

    private suspend fun importXtreamLive(source: SourceRecord, bundle: SecretBundle?, emit: (ContentItem) -> Unit): UnitOutcome {
        val endpoint = source.sourceTemplate?.let { XtreamEndpoint.parse(it.template) }
            ?: return UnitOutcome(ImportUnit.LIVE, ImportStatus.FAILED, 0, DomainError.Storage("ENDPOINT_MISSING"))
        val credentials = credentialsOf(bundle) ?: return missingCredentials()
        val result = xtream.importUnit(ImportUnit.LIVE, endpoint, credentials, source.playlistId, source.account, emit)
        return UnitOutcome(ImportUnit.LIVE, result.status, result.counts.channels, result.error)
    }

    private suspend fun importM3u(source: SourceRecord, bundle: SecretBundle?, emit: (ContentItem) -> Unit): UnitOutcome {
        val url = sourceUrl(source, bundle) ?: return missingCredentials()
        val response = when (val fetched = fetcher.get(url, RequestClass.LARGE_LIST, UrlContext.SOURCE)) {
            is FetchResult.Failure -> return UnitOutcome(ImportUnit.LIVE, ImportStatus.FAILED, 0, fetched.error)
            is FetchResult.Success -> fetched.response
        }
        val result = try {
            M3uImporter.import(response.body, source.playlistId, bundle?.let { SourceCredentials(it.username, it.password) }, emit = emit)
        } finally {
            response.body.close()
        }
        return when (val parse = result.outcome) {
            is ParseOutcome.Rejected -> UnitOutcome(ImportUnit.LIVE, ImportStatus.FAILED, 0, DomainError.Validation(parse.failure))
            is ParseOutcome.Stopped -> UnitOutcome(
                ImportUnit.LIVE,
                ImportStatus.PARTIAL,
                result.counts.channels,
                DomainError.Limit(parse.limit),
            )
            ParseOutcome.Completed -> UnitOutcome(ImportUnit.LIVE, ImportStatus.PUBLISHED, result.counts.channels, null)
        }
    }

    /**
     * Imports the source's XMLTV guide (keeping 1 day back and [lookAheadDays] ahead), links channels with [EpgMatcher] and
     * publishes. Channels must have been imported first.
     */
    public suspend fun refreshEpg(playlistId: PlaylistId, lookAheadDays: Int = 3): UnitOutcome {
        val source =
            content.source(playlistId) ?: return UnitOutcome(ImportUnit.EPG, ImportStatus.FAILED, 0, DomainError.Storage("SOURCE_MISSING"))
        val template =
            source.epgTemplate ?: return UnitOutcome(ImportUnit.EPG, ImportStatus.FAILED, 0, DomainError.Unsupported("NO_EPG_SOURCE"))
        val customLink = template.template == GUIDE_LINK_TEMPLATE
        val bundle = source.credentialRef?.let { secrets.get(it) }
        val url = if (customLink) {
            secrets.get(guideLinkRef(playlistId))?.secretUrl ?: return missingCredentials(ImportUnit.EPG)
        } else {
            template.expand(bundle?.username, bundle?.password) ?: return missingCredentials(ImportUnit.EPG)
        }
        val outcome = importGuide(playlistId, source, url, lookAheadDays)
        if (customLink || source.type != PlaylistType.XTREAM || outcome.guide?.code != GuideSummary.EMPTY) return outcome
        // An empty xmltv.php: many panels advertise a different guide in their playlist header (`url-tvg`).
        val (advertised, headerResult) = playlistHeaderGuide(source, bundle)
        if (advertised == null) return outcome.copy(guide = outcome.guide.copy(playlistHeader = headerResult))
        val retry = importGuide(playlistId, source, advertised, lookAheadDays)
        return retry.copy(guide = retry.guide?.copy(fromPlaylistHeader = true, playlistHeader = headerResult))
    }

    /**
     * The guide link in an Xtream panel's M3U header (`#EXTM3U url-tvg="…"`), reading only the first line of `get.php` and
     * closing the connection; null when absent or the same `xmltv.php`. The link is used for this refresh only, never stored.
     */
    private suspend fun playlistHeaderGuide(source: SourceRecord, bundle: SecretBundle?): Pair<SensitiveUrl?, String> {
        val endpoint = source.sourceTemplate?.let { XtreamEndpoint.parse(it.template) } ?: return null to "NO_ENDPOINT"
        val playlist =
            UrlTemplate(
                "${endpoint.base}/get.php?username=${UrlTemplate.USERNAME}&password=${UrlTemplate.PASSWORD}&type=m3u_plus&output=ts",
            )
                .expand(bundle?.username, bundle?.password) ?: return null to "NO_CREDENTIALS"
        val response = when (val fetched = fetcher.get(playlist, RequestClass.LARGE_LIST, UrlContext.SOURCE)) {
            is FetchResult.Failure -> return null to "FETCH_${fetched.error.code}"
            is FetchResult.Success -> fetched.response
        }
        val header = try {
            readFirstLine(response.body, HEADER_LIMIT_BYTES)
        } finally {
            response.body.close()
        }
        val providerGuide = endpoint.xmltvTemplate.expand(bundle?.username, bundle?.password)
        val link = advertisedGuide(header, providerGuide)
        val result = when {
            link != null -> "FOUND"
            !header.trimStart('\uFEFF', ' ').startsWith("#EXTM3U") -> "NOT_M3U(${header.length} chars)"
            !GUIDE_ATTRIBUTE.containsMatchIn(header) -> "NO_GUIDE_ATTRIBUTE"
            else -> "SAME_AS_PROVIDER_OR_NOT_HTTP"
        }
        return link to result
    }

    private suspend fun importGuide(playlistId: PlaylistId, source: SourceRecord, url: SensitiveUrl, lookAheadDays: Int): UnitOutcome {
        content.markUnit(playlistId, ImportUnit.EPG, ImportStatus.RUNNING)
        val response = when (val fetched = fetcher.get(url, RequestClass.LARGE_LIST, UrlContext.EPG)) {
            is FetchResult.Failure -> {
                content.markUnit(playlistId, ImportUnit.EPG, ImportStatus.FAILED, fetched.error.code)
                return UnitOutcome(ImportUnit.EPG, ImportStatus.FAILED, 0, fetched.error)
            }
            is FetchResult.Success -> fetched.response
        }
        val epgSource = EpgSourceId(playlistId.value)
        val now = clock.now()
        val snapshot = now.toEpochMilliseconds()
        epg.enableSearch()
        val writer = epg.beginSnapshot(epgSource, snapshot)
        val epgChannels = LinkedHashMap<String, EpgChannel>()
        val result = try {
            XmltvImporter.import(
                response.body,
                XmltvImportOptions(epgSource, retention = (now - 1.days)..(now + lookAheadDays.days)),
            ) { item ->
                when (item) {
                    is ContentItem.EpgChannelItem -> epgChannels[item.channel.key.channelId] = item.channel
                    is ContentItem.ProgramItem -> with(item.program) {
                        writer.add(ProgramRow(id, epgChannelKey.channelId, start, end, title, subtitle, description))
                    }
                    else -> Unit
                }
            }
        } finally {
            response.body.close()
        }
        if (result.status == ImportStatus.FAILED) {
            epg.discard(epgSource, snapshot)
            content.markUnit(playlistId, ImportUnit.EPG, ImportStatus.FAILED, result.error?.code)
            return UnitOutcome(ImportUnit.EPG, ImportStatus.FAILED, 0, result.error)
        }
        val written = writer.finish()
        epg.indexSearch(epgSource, snapshot)
        epg.activate(epgSource, snapshot)

        // Programmes may reference channels the document never declared; they can still match by ID.
        val channels = content.channels(playlistId)
        val declared = epgChannels.values.toMutableList()
        val match = EpgMatcher.match(
            channels.map { EpgMatchCandidate(it.id, it.name, it.tvgId, source.type == PlaylistType.XTREAM) },
            declared.ifEmpty {
                channels.mapNotNull { it.tvgId }.distinct().map {
                    EpgChannel(
                        EpgChannelKey(epgSource, it),
                        emptyList(),
                        null,
                    )
                }
            },
            sourcePriority = 0,
        )
        epg.replaceLinks(
            playlistId.value,
            match.links.map {
                ChannelEpgLinkRow(it.channelId.value, epgSource.value, it.epgChannelKey.channelId, it.method.name, it.confidence)
            },
        )
        val summary = GuideSummary(
            declaredChannels = result.counts.channels,
            // The importer counts emitted programmes; ones outside retention or dropped were read too.
            programmesRead = result.counts.programmes + result.counts.outsideRetention + result.counts.droppedProgrammes,
            programmesKept = written,
            outsideWindow = result.counts.outsideRetention,
            dropped = result.counts.droppedProgrammes,
            linkedChannels = match.links.size,
        )
        content.markUnit(playlistId, ImportUnit.EPG, result.status, summary.code, written.toLong())
        return UnitOutcome(ImportUnit.EPG, result.status, written, result.error, summary)
    }

    /**
     * Upcoming programmes from the provider's per-channel guide (Xtream `get_short_epg`), for channels that have no XMLTV
     * programmes. Only Xtream sources; at most 20 channels per call; results are cached in memory. Empty for other sources.
     */
    public suspend fun shortGuide(
        playlistId: PlaylistId,
        channelIds: List<ChannelId>,
        report: (ShortGuideReport) -> Unit = {},
    ): Map<String, List<GuideProgramme>> = shortGuide.programmes(playlistId, channelIds, report)

    /** Builds the playable stream for a channel immediately before playback (docs/PLAYBACK.md §2). */
    public suspend fun resolveChannel(
        playlistId: PlaylistId,
        channelId: ChannelId,
        preferred: PreferredStreamFormat = PreferredStreamFormat.AUTO,
    ): ResolveResult? {
        val source = content.source(playlistId) ?: return null
        val media = content.mediaSources(playlistId, channelId).firstOrNull() ?: return null
        val bundle = source.credentialRef?.let { secrets.get(it) }
        val headerSecrets = media.headers.sensitive.values.associateWith { ref -> secrets.get(ref)?.secretHeaders?.values?.firstOrNull() }
            .filterValues { it != null }.mapValues { it.value!! }
        val provider = Provider(
            id = source.providerId,
            displayName = source.name,
            protocol = if (source.type == PlaylistType.XTREAM) ProtocolId.XTREAM else ProtocolId.M3U,
            endpointDisplay = source.endpointDisplay,
            endpoint = source.sourceTemplate.takeIf { source.type == PlaylistType.XTREAM },
            credentialRef = source.credentialRef,
            capabilities = Capabilities.UNDISCOVERED,
            account = source.account,
            transportSecurity = source.transportSecurity,
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )
        return MediaSourceResolver.resolve(media, provider, bundle, headerSecrets, platform, preferred)
    }

    private fun sourceUrl(source: SourceRecord, bundle: SecretBundle?): SensitiveUrl? {
        val template = source.sourceTemplate ?: return null
        if (template.template == SECRET_URL_TEMPLATE) return bundle?.secretUrl
        return template.expand(bundle?.username, bundle?.password)
    }

    private fun credentialsOf(bundle: SecretBundle?): XtreamCredentials? {
        val username = bundle?.username ?: return null
        val password = bundle.password ?: return null
        return XtreamCredentials(username, password)
    }

    private fun missingCredentials(unit: ImportUnit = ImportUnit.LIVE) =
        UnitOutcome(unit, ImportStatus.FAILED, 0, DomainError.Auth(AuthFailure.MISSING_CREDENTIALS))

    internal companion object {
        /** Marks a source whose whole URL lives in the secret store. */
        const val SECRET_URL_TEMPLATE = "{credential:url}"

        const val HEADER_LIMIT_BYTES = 64 * 1024

        private val GUIDE_ATTRIBUTE = Regex("""(?:url-tvg|x-tvg-url)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

        /** First line of [source] (up to [limit] bytes), decoded as UTF-8. */
        fun readFirstLine(source: app.iptvplayer.domain.ports.ByteSource, limit: Int): String {
            val buffer = ByteArray(limit)
            var size = 0
            while (size < limit) {
                val read = source.read(buffer, size, minOf(8192, limit - size))
                if (read <= 0) break
                val newline = (size until size + read).firstOrNull { buffer[it] == '\n'.code.toByte() }
                size += read
                if (newline != null) return buffer.decodeToString(0, newline)
            }
            return buffer.decodeToString(0, size)
        }

        /** The first http(s) guide link in an `#EXTM3U` header line, unless it is [providerGuide] itself. */
        fun advertisedGuide(header: String, providerGuide: SensitiveUrl?): SensitiveUrl? {
            if (!header.trimStart('\uFEFF', ' ').startsWith("#EXTM3U")) return null
            val value = GUIDE_ATTRIBUTE.find(header)?.groupValues?.get(1) ?: return null
            val link = value.split(',').map { it.trim() }.firstOrNull {
                it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
            } ?: return null
            if (providerGuide != null && link == providerGuide.unsafeRawValue()) return null
            return SensitiveUrl.of(link)
        }

        /** Marks a guide link set by the user; the URL itself lives in the secret store. */
        const val GUIDE_LINK_TEMPLATE = "{credential:guide-link}"

        fun failureOf(error: DomainError): AddSourceFailure = when (error) {
            is DomainError.Auth -> when (error.reason) {
                AuthFailure.ACCOUNT_EXPIRED -> AddSourceFailure.ACCOUNT_EXPIRED
                AuthFailure.ACCOUNT_BANNED, AuthFailure.ACCOUNT_DISABLED -> AddSourceFailure.ACCOUNT_DISABLED
                else -> AddSourceFailure.INVALID_CREDENTIALS
            }
            is DomainError.Network -> AddSourceFailure.CONNECTION_FAILED
            is DomainError.Http -> if (error.status == 401 ||
                error.status == 403
            ) {
                AddSourceFailure.INVALID_CREDENTIALS
            } else {
                AddSourceFailure.SERVER_ERROR
            }
            is DomainError.Validation -> if (error.reason ==
                ValidationFailure.URL_REJECTED
            ) {
                AddSourceFailure.INVALID_URL
            } else {
                AddSourceFailure.NOT_A_PLAYLIST
            }
            else -> AddSourceFailure.NOT_A_PLAYLIST
        }
    }
}
