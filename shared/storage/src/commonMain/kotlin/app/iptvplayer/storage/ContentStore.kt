package app.iptvplayer.storage

import app.cash.sqldelight.db.SqlDriver
import app.iptvplayer.domain.capability.StreamFormat
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.model.AccountStatus
import app.iptvplayer.domain.model.Channel
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.ProtocolId
import app.iptvplayer.domain.model.ProviderAccount
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.db.IptvDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A configured source as shown in the UI: one provider with one playlist (V1 onboarding creates them together). */
public data class SourceRecord(
    public val playlistId: PlaylistId,
    public val providerId: ProviderId,
    public val name: String,
    public val type: PlaylistType,
    public val endpointDisplay: String,
    public val transportSecurity: TransportSecurity,
    public val credentialRef: CredentialRef?,
    /** Xtream base URL or M3U URL, with credential placeholders. */
    public val sourceTemplate: UrlTemplate?,
    public val epgTemplate: UrlTemplate?,
    public val account: ProviderAccount?,
)

public data class UnitStateRecord(
    public val unit: ImportUnit,
    public val activeSnapshot: Long?,
    public val status: ImportStatus,
    public val itemCount: Long,
    public val errorCode: String?,
)

public data class GroupRow(public val id: String, public val title: String, public val channelCount: Long)

public data class ChannelRow(
    public val id: ChannelId,
    public val name: String,
    public val number: Int?,
    public val logo: UrlTemplate?,
    public val tvgId: String?,
    public val isFavorite: Boolean,
)

/**
 * Sources, live channels, groups, stream locators and favorites (docs/DOMAIN_MODEL.md §9). Imports write a new snapshot
 * through [LiveSnapshotWriter]; readers only ever see the published one.
 */
public class ContentStore(driver: SqlDriver, private val clock: Clock) {
    private val database = IptvDatabase(driver)
    private val queries = database.contentQueries

    public fun addSource(
        playlistId: PlaylistId,
        providerId: ProviderId,
        name: String,
        type: PlaylistType,
        endpointDisplay: String,
        sourceTemplate: UrlTemplate,
        credentialRef: CredentialRef?,
        transportSecurity: TransportSecurity,
    ) {
        val now = clock.now().toEpochMilliseconds()
        val protocol = if (type == PlaylistType.XTREAM) ProtocolId.XTREAM else ProtocolId.M3U
        database.transaction {
            queries.insertProvider(
                providerId.value, name, protocol.name, endpointDisplay, sourceTemplate.template, credentialRef?.value,
                transportSecurity.name, null, null, null, null, now, now,
            )
            queries.insertPlaylist(playlistId.value, providerId.value, name, type.name, sourceTemplate.template, null, now)
        }
    }

    public fun updateAccount(providerId: ProviderId, account: ProviderAccount) {
        queries.updateProviderAccount(
            account.status.name,
            account.expiresAt?.toEpochMilliseconds(),
            account.maxConnections?.toLong(),
            account.allowedOutputFormats.joinToString(",") { it.name },
            clock.now().toEpochMilliseconds(),
            providerId.value,
        )
    }

    public fun setEpgTemplate(playlistId: PlaylistId, template: UrlTemplate?) {
        queries.setPlaylistEpgTemplate(template?.template, playlistId.value)
    }

    public fun sources(): List<SourceRecord> = queries.playlists().executeAsList().mapNotNull { source(PlaylistId(it.id)) }

    public fun source(playlistId: PlaylistId): SourceRecord? {
        val playlist = queries.playlistById(playlistId.value).executeAsOneOrNull() ?: return null
        val provider = queries.providerById(playlist.provider_id).executeAsOneOrNull() ?: return null
        val account = provider.account_status?.let { status ->
            ProviderAccount(
                status = AccountStatus.valueOf(status),
                expiresAt = provider.account_expires_at?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
                isTrial = null,
                maxConnections = provider.max_connections?.toInt(),
                activeConnections = null,
                allowedOutputFormats = provider.allowed_output_formats.orEmpty().split(',').filter { it.isNotEmpty() }
                    .mapNotNull { name -> StreamFormat.entries.firstOrNull { it.name == name } }.toSet(),
                serverTimezone = null,
            )
        }
        return SourceRecord(
            playlistId = playlistId,
            providerId = ProviderId(provider.id),
            name = playlist.name,
            type = PlaylistType.valueOf(playlist.type),
            endpointDisplay = provider.endpoint_display,
            transportSecurity = TransportSecurity.valueOf(provider.transport_security),
            credentialRef = provider.credential_ref?.let { CredentialRef(it) },
            sourceTemplate = playlist.source_template?.let { UrlTemplate(it) },
            epgTemplate = playlist.epg_template?.let { UrlTemplate(it) },
            account = account,
        )
    }

    /** Removes the source and all its imported content; favorites of its channels are kept (user state). */
    public fun deleteSource(playlistId: PlaylistId) {
        val providerId = queries.playlistById(playlistId.value).executeAsOneOrNull()?.provider_id ?: return
        database.transaction {
            queries.deleteAllMembers(playlistId.value)
            queries.deleteAllMediaSources(playlistId.value)
            queries.deleteAllChannels(playlistId.value)
            queries.deleteAllGroups(playlistId.value)
            queries.deletePlaylistRow(playlistId.value)
            queries.deleteProviderRow(providerId)
        }
    }

    public fun unitState(playlistId: PlaylistId, unit: ImportUnit): UnitStateRecord? =
        queries.unitState(playlistId.value, unit.name).executeAsOneOrNull()?.let {
            UnitStateRecord(unit, it.active_snapshot, ImportStatus.valueOf(it.status), it.item_count, it.error_code)
        }

    /**
     * Records a unit's status without touching its published snapshot. [itemCount] replaces the stored count when given (for
     * example programmes kept by a guide import).
     */
    public fun markUnit(
        playlistId: PlaylistId,
        unit: ImportUnit,
        status: ImportStatus,
        errorCode: String? = null,
        itemCount: Long? = null,
    ) {
        val previous = unitState(playlistId, unit)
        val now = clock.now().toEpochMilliseconds()
        queries.upsertUnitState(
            playlistId.value,
            unit.name,
            previous?.activeSnapshot,
            status.name,
            itemCount ?: previous?.itemCount ?: 0,
            if (status == ImportStatus.RUNNING) now else null,
            if (status == ImportStatus.RUNNING) null else now,
            errorCode,
        )
    }

    public fun beginLiveSnapshot(playlistId: PlaylistId, batchSize: Int = 500): LiveSnapshotWriter {
        val previous = unitState(playlistId, ImportUnit.LIVE)?.activeSnapshot ?: 0L
        return LiveSnapshotWriter(playlistId, maxOf(previous + 1, clock.now().toEpochMilliseconds()), batchSize)
    }

    private fun activeLiveSnapshot(playlistId: PlaylistId): Long? = unitState(playlistId, ImportUnit.LIVE)?.activeSnapshot

    public fun groups(playlistId: PlaylistId): List<GroupRow> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyList()
        return queries.groupsWithCounts(playlistId.value, snapshot).executeAsList().map { GroupRow(it.id, it.title, it.channel_count) }
    }

    public fun channels(playlistId: PlaylistId, groupId: String? = null): List<ChannelRow> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyList()
        return if (groupId == null) {
            queries.allChannels(
                playlistId.value,
                snapshot,
            ) { id, name, number, logo, tvg, favorite -> row(id, name, number, logo, tvg, favorite) }
        } else {
            queries.channelsInGroup(
                playlistId.value,
                snapshot,
                groupId,
            ) { id, name, number, logo, tvg, favorite -> row(id, name, number, logo, tvg, favorite) }
        }.executeAsList()
    }

    public fun favoriteChannels(playlistId: PlaylistId): List<ChannelRow> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyList()
        return queries.favoriteChannels(playlistId.value, snapshot) { id, name, number, logo, tvg, _ ->
            row(id, name, number, logo, tvg, true)
        }.executeAsList()
    }

    public fun channelCount(playlistId: PlaylistId): Long =
        activeLiveSnapshot(playlistId)?.let { queries.channelCount(playlistId.value, it).executeAsOne() } ?: 0

    public fun setFavorite(channelId: ChannelId, favorite: Boolean) {
        if (favorite) {
            queries.addFavorite(ContentType.CHANNEL.name, channelId.value, clock.now().toEpochMilliseconds())
        } else {
            queries.removeFavorite(ContentType.CHANNEL.name, channelId.value)
        }
    }

    /** The playable sources of [channelId] in the active snapshot, best first. */
    public fun mediaSources(playlistId: PlaylistId, channelId: ChannelId): List<MediaSource> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyList()
        return queries.mediaSourcesForOwner(playlistId.value, snapshot, channelId.value).executeAsList().map { row ->
            val locator = when (row.locator_kind) {
                LOCATOR_XTREAM -> MediaLocator.XtreamStream(XtreamStreamKind.valueOf(row.xtream_kind!!), row.stream_id!!, row.extension)
                else -> MediaLocator.DirectUrl(UrlTemplate(row.url_template!!))
            }
            MediaSource(
                id = MediaSourceId(row.id),
                owner = ContentRef(ContentType.CHANNEL, row.owner_id),
                locator = locator,
                protocolHint = StreamProtocol.valueOf(row.protocol),
                headers = MediaHeaders(
                    userAgent = row.user_agent,
                    referrer = row.referrer,
                    custom = decodeMap(row.custom_headers),
                    sensitive = decodeMap(row.sensitive_headers).mapValues { CredentialRef(it.value) },
                ),
                drm = null,
                codecHints = null,
                priority = row.priority.toInt(),
            )
        }
    }

    private fun row(id: String, name: String, number: Long?, logo: String?, tvgId: String?, favorite: Boolean?) =
        ChannelRow(ChannelId(id), name, number?.toInt(), logo?.let { UrlTemplate(it) }, tvgId, favorite == true)

    /** Writes one LIVE snapshot in batched transactions; nothing is visible until [publish]. */
    public inner class LiveSnapshotWriter internal constructor(
        public val playlistId: PlaylistId,
        public val snapshot: Long,
        private val batchSize: Int,
    ) {
        private val pending = ArrayList<() -> Unit>(batchSize)
        private var groupOrder = 0L
        private var channelOrder = 0L
        private var memberOrder = 0L
        public var channelCount: Int = 0
            private set

        public fun group(group: ChannelGroup) {
            val order = groupOrder++
            add { queries.insertGroup(playlistId.value, snapshot, group.id.value, group.title, order) }
        }

        public fun channel(channel: Channel, source: MediaSource, logo: UrlTemplate?) {
            val order = channelOrder++
            channelCount++
            add {
                queries.insertChannel(
                    playlistId.value, snapshot, channel.id.value, channel.name, channel.number?.toLong(), order, logo?.template,
                    channel.tvgId, channel.catchUp?.days?.toLong(), channel.isAdult?.let { if (it) 1L else 0L },
                )
                insertSource(source)
            }
            channel.groupIds.forEach { membership(channel.id, it.value) }
        }

        public fun membership(channelId: ChannelId, groupId: String) {
            val order = memberOrder++
            add { queries.insertMember(playlistId.value, snapshot, groupId, channelId.value, order) }
        }

        private fun insertSource(source: MediaSource) {
            val locator = source.locator
            queries.insertMediaSource(
                playlistId.value, snapshot, source.id.value, source.owner.id,
                if (locator is MediaLocator.XtreamStream) LOCATOR_XTREAM else LOCATOR_DIRECT,
                (locator as? MediaLocator.DirectUrl)?.template?.template,
                (locator as? MediaLocator.XtreamStream)?.kind?.name,
                (locator as? MediaLocator.XtreamStream)?.streamId,
                (locator as? MediaLocator.XtreamStream)?.extension,
                source.protocolHint.name, source.headers.userAgent, source.headers.referrer,
                encodeMap(
                    source.headers.custom,
                ),
                encodeMap(source.headers.sensitive.mapValues { it.value.value }), source.priority.toLong(),
            )
        }

        private fun add(write: () -> Unit) {
            pending += write
            if (pending.size >= batchSize) flush()
        }

        private fun flush() {
            if (pending.isEmpty()) return
            database.transaction { pending.forEach { it() } }
            pending.clear()
        }

        /** Makes this snapshot the one readers see and deletes the previous one. */
        public fun publish() {
            flush()
            val previous = activeLiveSnapshot(playlistId)
            val now = clock.now().toEpochMilliseconds()
            database.transaction {
                queries.upsertUnitState(
                    playlistId.value,
                    ImportUnit.LIVE.name,
                    snapshot,
                    ImportStatus.PUBLISHED.name,
                    channelCount.toLong(),
                    null,
                    now,
                    null,
                )
                if (previous != null && previous != snapshot) deleteSnapshot(previous)
            }
        }

        /** Throws away everything written for this snapshot; the previous snapshot stays active. */
        public fun discard() {
            pending.clear()
            database.transaction { deleteSnapshot(snapshot) }
        }

        private fun deleteSnapshot(version: Long) {
            queries.deleteMembers(playlistId.value, version)
            queries.deleteMediaSources(playlistId.value, version)
            queries.deleteChannels(playlistId.value, version)
            queries.deleteGroups(playlistId.value, version)
        }
    }

    private companion object {
        const val LOCATOR_DIRECT = "DIRECT_URL"
        const val LOCATOR_XTREAM = "XTREAM_STREAM"

        fun encodeMap(map: Map<String, String>): String? = if (map.isEmpty()) {
            null
        } else {
            JsonObject(
                map.mapValues {
                    JsonPrimitive(it.value)
                },
            ).toString()
        }

        fun decodeMap(text: String?): Map<String, String> =
            text?.let { Json.parseToJsonElement(it).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.content } }.orEmpty()
    }
}
