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
import app.iptvplayer.domain.model.CustomisationTarget
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

public data class GroupRow(
    public val id: String,
    public val title: String,
    public val channelCount: Long,
    /** For the viewer's own groups: how many films and shows they hold (a provider category holds none). */
    public val movieCount: Long = 0,
    public val seriesCount: Long = 0,
)

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
public class ContentStore(private val driver: SqlDriver, private val clock: Clock) {
    private val database = IptvDatabase(driver)
    private val queries = database.contentQueries
    internal val libraryQueries = database.libraryQueries
    internal val searchQueries = database.searchQueries
    internal val detailQueries = database.detailQueries
    internal val browseQueries = database.browseQueries
    private val customisationQueries = database.customisationQueries

    /** Marks [snapshot] as the published content of [unit] (library units; live channels use their writer). */
    internal fun publishUnit(playlistId: PlaylistId, unit: ImportUnit, snapshot: Long, itemCount: Long) {
        queries.upsertUnitState(
            playlistId.value,
            unit.name,
            snapshot,
            ImportStatus.PUBLISHED.name,
            itemCount,
            null,
            clock.now().toEpochMilliseconds(),
            null,
        )
    }

    /** Runs [block] in one database transaction (used by the library store). */
    internal fun <T> transaction(block: () -> T): T = database.transactionWithResult { block() }

    /**
     * A new snapshot number for [playlistId], larger than any before for this playlist. Units share the media_source table, so
     * two units must never publish the same snapshot number (a later delete of one would remove the other's rows).
     */
    internal fun allocateSnapshot(playlistId: PlaylistId): Long = database.transactionWithResult {
        libraryQueries.allocateSnapshot(playlistId.value, clock.now().toEpochMilliseconds())
        libraryQueries.allocatedSnapshot(playlistId.value).executeAsOne()
    }

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
            libraryQueries.deleteLibrary(playlistId.value)
            searchQueries.deleteTitlesOfPlaylist(playlistId.value)
            customisationQueries.deleteCustomisation(playlistId.value)
            queries.deletePlaylistRow(playlistId.value)
            queries.deleteProviderRow(providerId)
        }
    }

    /**
     * Folds the write-ahead log back into the database file. An import writes tens of thousands of rows; until that log is
     * folded in, every read has to look through it, which measured 6x slower on the owner's Bbox TV right after an import
     * (PERFORMANCE.md §6). Callers run this off the main thread after publishing. Busy readers only postpone it.
     */
    public fun checkpoint() {
        driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
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
        return LiveSnapshotWriter(playlistId, maxOf(previous + 1, allocateSnapshot(playlistId)), batchSize)
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

    /**
     * Channels matching [query] through the title index: exact name first, then names starting with it, then the rest
     * (FR-SRCH-002). Matching on the index instead of scanning every name is what keeps this inside the 50 ms budget on a
     * large playlist (PERFORMANCE.md §1).
     */
    public fun searchChannels(playlistId: PlaylistId, query: String, limit: Int): List<ChannelRow> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyList()
        val ids = searchTitles(playlistId, snapshot, ContentType.CHANNEL, query, limit)
        if (ids.isEmpty()) return emptyList()
        val found = queries.channelsByIds(playlistId.value, snapshot, ids) { id, name, number, logo, tvg, favorite ->
            row(id, name, number, logo, tvg, favorite)
        }.executeAsList().associateBy { it.id.value }
        return ids.mapNotNull { found[it] }
    }

    /**
     * The content ids of [type] matching [query], best match first: the title typed exactly, then titles starting with it,
     * then the rest in the provider's own order.
     *
     * The index is read in insertion order and stops at [CANDIDATES]; the ranking happens here, over that handful of rows.
     * Ranking inside the query instead means reading every match, and a word that appears in all 20,000 titles of a large
     * provider then costs 280-500 ms on the owner's Bbox TV against a 50 ms budget (PERFORMANCE.md §1, ADR-0029). A query
     * selective enough to return fewer than [CANDIDATES] rows is ranked exactly, which is every realistic search.
     */
    internal fun searchTitles(playlistId: PlaylistId, snapshot: Long, type: ContentType, query: String, limit: Int): List<String> {
        val match = TitleIndex.match(query) ?: return emptyList()
        val candidates = (limit * 8).coerceIn(limit, CANDIDATES)
        // FTS5 columns are untyped, so the snapshot is stored and compared as text.
        val rows = searchQueries
            .searchTitles(match, type.name, playlistId.value, snapshot.toString(), candidates.toLong())
            .executeAsList()
        val typed = query.trim().lowercase()
        return rows.sortedBy { row ->
            val title = row.title?.lowercase()
            when {
                title == typed -> 0
                title?.startsWith(typed) == true -> 1
                else -> 2
            }
        }.mapNotNull { it.content_id }.take(limit)
    }

    /**
     * The viewer's own choices about this source (FR-PLM-001): what to hide, and what to call a category.
     *
     * They live in their own tables, keyed by the same stable ids as favorites, so a refresh — which replaces every
     * imported row — leaves them untouched. Nothing here changes what the provider sent: hiding is a filter on the way
     * out, and a rename is a label beside the original title, which is still there if the label is cleared.
     */
    public fun hide(playlistId: PlaylistId, type: CustomisationTarget, id: String) {
        customisationQueries.hide(playlistId.value, type.name, id, clock.now().toEpochMilliseconds())
    }

    public fun unhide(playlistId: PlaylistId, type: CustomisationTarget, id: String) {
        customisationQueries.unhide(playlistId.value, type.name, id)
    }

    /** The ids of [type] the viewer has hidden, oldest first. */
    public fun hidden(playlistId: PlaylistId, type: CustomisationTarget): List<String> =
        customisationQueries.hiddenOf(playlistId.value, type.name).executeAsList()

    public fun hiddenCount(playlistId: PlaylistId): Long = customisationQueries.hiddenCount(playlistId.value).executeAsOne()

    /** Renames one thing for this viewer. A blank [label] restores the provider's own name. */
    public fun setLabel(playlistId: PlaylistId, type: CustomisationTarget, id: String, label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) {
            customisationQueries.clearLabel(playlistId.value, type.name, id)
        } else {
            customisationQueries.setLabel(playlistId.value, type.name, id, trimmed)
        }
    }

    /** The viewer's names for [type], by content id. */
    public fun labels(playlistId: PlaylistId, type: CustomisationTarget): Map<String, String> =
        customisationQueries.labelsOf(playlistId.value, type.name).executeAsList().associate { it.content_id to it.label }

    /**
     * A small app-wide preference, such as which Home rows the viewer wants and in what order. Null means they have not
     * chosen — which is not the same as choosing nothing.
     */
    public fun preference(key: String): String? = customisationQueries.setting(key).executeAsOneOrNull()

    public fun setPreference(key: String, value: String?) {
        if (value == null) customisationQueries.clearSetting(key) else customisationQueries.setSetting(key, value)
    }

    /**
     * The viewer's own groups (FR-PLM-001, FR-FAV-002): a name and the content they put in it. Members are stable
     * content ids, so a refresh — which replaces every imported row — leaves the groups intact; deleting the source
     * removes them, and nothing else does.
     */
    public fun createGroup(playlistId: PlaylistId, id: String, title: String): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return false
        val order = customisationQueries.nextGroupOrder(playlistId.value).executeAsOne()
        customisationQueries.createGroup(playlistId.value, id, trimmed, order, clock.now().toEpochMilliseconds())
        return true
    }

    public fun renameGroup(playlistId: PlaylistId, id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) customisationQueries.renameGroup(trimmed, playlistId.value, id)
    }

    public fun deleteGroup(playlistId: PlaylistId, id: String) {
        customisationQueries.deleteGroup(playlistId.value, id)
    }

    public fun userGroups(playlistId: PlaylistId): List<GroupRow> = customisationQueries.userGroups(playlistId.value).executeAsList()
        .map { GroupRow(it.id, it.title, it.channel_count, it.movie_count, it.series_count) }

    public fun addToGroup(playlistId: PlaylistId, groupId: String, type: ContentType, id: String) {
        val order = customisationQueries.nextMemberOrder(playlistId.value, groupId).executeAsOne()
        customisationQueries.addMember(playlistId.value, groupId, type.name, id, order)
    }

    public fun removeFromGroup(playlistId: PlaylistId, groupId: String, type: ContentType, id: String) {
        customisationQueries.removeMember(playlistId.value, groupId, type.name, id)
    }

    /** The viewer's groups that already hold this item, so a menu can offer to take it out again. */
    public fun groupsHolding(playlistId: PlaylistId, type: ContentType, id: String): List<String> =
        customisationQueries.groupsHolding(playlistId.value, type.name, id).executeAsList()

    /** The channels of one of the viewer's groups, in the order they were added. */
    public fun channelsInUserGroup(playlistId: PlaylistId, groupId: String): List<ChannelRow> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyList()
        return customisationQueries.channelsInUserGroup(snapshot, playlistId.value, groupId) { id, name, number, logo, tvg, favorite ->
            row(id, name, number, logo, tvg, favorite)
        }.executeAsList()
    }

    /** Names for [ids], including things the viewer has hidden — the hidden list has to name what it offers back. */
    public fun channelNames(playlistId: PlaylistId, ids: List<String>): Map<String, String> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        return queries.channelNames(playlistId.value, snapshot, ids).executeAsList().associate { it.id to it.name }
    }

    public fun groupNames(playlistId: PlaylistId, ids: List<String>): Map<String, String> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        return queries.groupNames(playlistId.value, snapshot, ids).executeAsList().associate { it.id to it.title }
    }

    public fun channelCount(playlistId: PlaylistId): Long =
        activeLiveSnapshot(playlistId)?.let { queries.channelCount(playlistId.value, it).executeAsOne() } ?: 0

    public fun setFavorite(channelId: ChannelId, favorite: Boolean) {
        setFavorite(ContentType.CHANNEL, channelId.value, favorite)
    }

    /** Favorites are user state keyed by content type and stable id; imports never delete them. */
    public fun setFavorite(type: ContentType, id: String, favorite: Boolean) {
        if (favorite) {
            queries.addFavorite(type.name, id, clock.now().toEpochMilliseconds())
        } else {
            queries.removeFavorite(type.name, id)
        }
    }

    /** The playable sources of [channelId] in the active snapshot, best first. */
    public fun mediaSources(playlistId: PlaylistId, channelId: ChannelId): List<MediaSource> {
        val snapshot = activeLiveSnapshot(playlistId) ?: return emptyList()
        return mediaSources(playlistId, snapshot, ContentType.CHANNEL, channelId.value)
    }

    internal fun mediaSources(playlistId: PlaylistId, snapshot: Long, ownerType: ContentType, ownerId: String): List<MediaSource> =
        queries.mediaSourcesForOwner(playlistId.value, snapshot, ownerId).executeAsList().map { row ->
            val locator = when (row.locator_kind) {
                LOCATOR_XTREAM -> MediaLocator.XtreamStream(XtreamStreamKind.valueOf(row.xtream_kind!!), row.stream_id!!, row.extension)
                else -> MediaLocator.DirectUrl(UrlTemplate(row.url_template!!))
            }
            MediaSource(
                id = MediaSourceId(row.id),
                owner = ContentRef(ownerType, row.owner_id),
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
                searchQueries.insertTitle(
                    channel.name,
                    ContentType.CHANNEL.name,
                    playlistId.value,
                    snapshot.toString(),
                    channel.id.value,
                )
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

        private fun insertSource(source: MediaSource) = insertMediaSource(playlistId, snapshot, source)

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
            checkpoint()
        }

        /** Throws away everything written for this snapshot; the previous snapshot stays active. */
        public fun discard() {
            pending.clear()
            database.transaction { deleteSnapshot(snapshot) }
        }

        private fun deleteSnapshot(version: Long) {
            searchQueries.deleteTitlesOfSnapshot(playlistId.value, version.toString())
            queries.deleteMembers(playlistId.value, version)
            queries.deleteMediaSources(playlistId.value, version)
            queries.deleteChannels(playlistId.value, version)
            queries.deleteGroups(playlistId.value, version)
        }
    }

    internal fun insertMediaSource(playlistId: PlaylistId, snapshot: Long, source: MediaSource) {
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

    internal companion object {
        /** How many index rows one search reads before it stops; see [searchTitles]. */
        const val CANDIDATES = 200
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

        /** [query] trimmed with LIKE wildcards escaped; null when blank. */
        internal fun likePattern(query: String): String? =
            query.trim().ifEmpty { null }?.replace("\\", "\\\\")?.replace("%", "\\%")?.replace("_", "\\_")

        fun decodeMap(text: String?): Map<String, String> =
            text?.let { Json.parseToJsonElement(it).jsonObject.mapValues { entry -> entry.value.jsonPrimitive.content } }.orEmpty()
    }
}
