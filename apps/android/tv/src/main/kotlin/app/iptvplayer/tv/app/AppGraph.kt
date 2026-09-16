package app.iptvplayer.tv.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.edit
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.ingestion.AddSourceFailure
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.ingestion.ShortGuideReport
import app.iptvplayer.ingestion.SourceService
import app.iptvplayer.ingestion.UnitOutcome
import app.iptvplayer.platform.AndroidPlatformCapabilities
import app.iptvplayer.platform.SystemClock
import app.iptvplayer.platform.net.OkHttpTransport
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.platform.secrets.KeystoreSecretStore
import app.iptvplayer.protocols.media.ResolveResult
import app.iptvplayer.storage.BundledSqliteDriver
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.ContinueItem
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.storage.GroupRow
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.storage.LibraryGroupRow
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.storage.SeasonRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.storage.SourceRecord
import app.iptvplayer.storage.UnitStateRecord
import app.iptvplayer.storage.db.IptvDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Instant

data class SearchResults(val query: String, val channels: List<ChannelRow>, val movies: List<MovieRow>, val series: List<SeriesRow>)

/** A movie or episode on Home's "Continue watching" row. */
data class ContinueCard(
    val type: ContentType,
    val id: String,
    val title: String,
    val subtitle: String?,
    val poster: UrlTemplate?,
    val fraction: Float?,
)

/** Import activity per source, for the UI. */
data class SourceActivity(
    val liveRunning: Boolean = false,
    val guideRunning: Boolean = false,
    val moviesRunning: Boolean = false,
    val seriesRunning: Boolean = false,
)

/**
 * Application-scoped composition root (ARCHITECTURE.md §10): one database, the Keystore secret store, the shared import
 * service and the operations screens need. All blocking work runs on [Dispatchers.IO]; [revision] increments whenever
 * imported content or favorites change so screens reload.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val driver by lazy {
        val file = appContext.getDatabasePath("iptv.db").apply { parentFile?.mkdirs() }
        BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    }
    private val secrets by lazy { KeystoreSecretStore(appContext) }
    private val content by lazy { ContentStore(driver, SystemClock) }
    private val library get() = service.library
    private val epg by lazy { EpgStore(driver) }
    private val service by lazy {
        SourceService(
            OkHttpTransport(::networkAvailable),
            secrets,
            content,
            epg,
            SystemClock,
            AndroidPlatformCapabilities,
        )
    }

    private val mutableRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    private val mutableActivity = MutableStateFlow<Map<PlaylistId, SourceActivity>>(emptyMap())
    val activity: StateFlow<Map<PlaylistId, SourceActivity>> = mutableActivity.asStateFlow()

    private fun changed() = mutableRevision.update { it + 1 }

    private val mutableWatchRevision = MutableStateFlow(0)

    /** Increments after watch progress is saved, so detail screens and Home refresh without reloading channel lists. */
    val watchRevision: StateFlow<Int> = mutableWatchRevision.asStateFlow()

    private fun networkAvailable(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun sources(): List<SourceRecord> = io { content.sources() }

    // The source Live TV and the guide show. A UI preference (a playlist id, nothing secret); falls back to the first source.
    private val preferences by lazy { appContext.getSharedPreferences("ui", Context.MODE_PRIVATE) }

    suspend fun currentSource(): SourceRecord? = io {
        val all = content.sources()
        val selected = preferences.getString(KEY_CURRENT_SOURCE, null)
        all.firstOrNull { it.playlistId.value == selected } ?: all.firstOrNull()
    }

    suspend fun selectSource(playlistId: PlaylistId) {
        io { preferences.edit(commit = true) { putString(KEY_CURRENT_SOURCE, playlistId.value) } }
        changed()
    }

    suspend fun channelCount(playlistId: PlaylistId): Long = io { content.channelCount(playlistId) }

    suspend fun unitStatus(playlistId: PlaylistId, unit: ImportUnit): ImportStatus? = io { content.unitState(playlistId, unit)?.status }

    suspend fun groups(playlistId: PlaylistId): List<GroupRow> = io { content.groups(playlistId) }

    suspend fun channels(playlistId: PlaylistId, groupId: String?): List<ChannelRow> = io { content.channels(playlistId, groupId) }

    suspend fun favoriteChannels(playlistId: PlaylistId): List<ChannelRow> = io { content.favoriteChannels(playlistId) }

    /**
     * Stored now/next for a whole channel list, read and assembled off the main thread (PERFORMANCE.md §5). A category of
     * a large provider holds thousands of channels: building that map where the UI runs cost frames on the owner's Bbox TV.
     */
    suspend fun storedNowNext(playlistId: PlaylistId, channels: List<ChannelRow>, now: Instant): Map<String, NowNextRow> = io {
        buildMap {
            channels.chunked(GUIDE_BATCH).forEach { chunk -> putAll(epg.nowNext(playlistId.value, chunk.map { it.id.value }, now)) }
        }
    }

    /**
     * Now/next from the stored guide; for a few channels (the ones on screen) without stored programmes, from the provider's
     * per-channel guide instead (Xtream sources, SourceService.shortGuide).
     */
    suspend fun nowNext(playlistId: PlaylistId, channels: List<ChannelId>, now: Instant): Map<String, NowNextRow> = io {
        val stored = epg.nowNext(playlistId.value, channels.map { it.value }, now)
        val missing = channels.filter { it.value !in stored }
        if (missing.isEmpty() || channels.size > SHORT_GUIDE_MAX_CHANNELS) return@io stored
        stored + service.shortGuide(playlistId, missing, ::logShortGuide).mapValues { (_, programmes) ->
            NowNextRow(programmes.firstOrNull { it.start <= now && now < it.end }, programmes.firstOrNull { it.start > now })
        }.filterValues { it.current != null || it.next != null }
    }

    suspend fun programmes(
        playlistId: PlaylistId,
        channels: List<ChannelId>,
        from: Instant,
        until: Instant,
    ): Map<String, List<GuideProgramme>> = io {
        val stored = epg.programmes(playlistId.value, channels.map { it.value }, from, until)
        val missing = channels.filter { stored[it.value].isNullOrEmpty() }
        if (missing.isEmpty() || channels.size > SHORT_GUIDE_MAX_CHANNELS) return@io stored
        stored + service.shortGuide(playlistId, missing, ::logShortGuide).mapValues { (_, programmes) ->
            programmes.filter { it.end > from && it.start < until }
        }.filterValues { it.isNotEmpty() }
    }

    suspend fun setFavorite(channelId: ChannelId, favorite: Boolean) {
        io { content.setFavorite(channelId, favorite) }
        changed()
    }

    /**
     * The viewer's own choices about this source (FR-PLM-001): hide something, bring it back, or give it another name.
     * Each one changes what the screens show and nothing about what the provider sent, so a refresh keeps them and
     * clearing a name brings the provider's own back.
     */
    suspend fun hide(playlistId: PlaylistId, target: CustomisationTarget, id: String) {
        io { content.hide(playlistId, target, id) }
        changed()
    }

    suspend fun unhide(playlistId: PlaylistId, target: CustomisationTarget, id: String) {
        io { content.unhide(playlistId, target, id) }
        changed()
    }

    suspend fun hidden(playlistId: PlaylistId, target: CustomisationTarget): List<String> = io { content.hidden(playlistId, target) }

    /**
     * Which rows Home shows, in order (PRODUCT_DIRECTIVE.md §3). Null until the viewer chooses, which leaves Home to
     * decide for itself from what the source holds; an empty list is a real choice — a Home with only the greeting.
     */
    suspend fun homeRows(): List<String>? = io {
        content.preference(HOME_ROWS)?.split(",")?.filter { it.isNotBlank() }
    }

    suspend fun setHomeRows(rows: List<String>?) {
        io { content.setPreference(HOME_ROWS, rows?.joinToString(",")) }
        changed()
    }

    /** The viewer's own groups: making one, filling it, and what is in it (FR-PLM-001, FR-FAV-002). */
    suspend fun createGroup(playlistId: PlaylistId, title: String): String? {
        val id = "ugrp_" + SystemClock.now().toEpochMilliseconds().toString(RADIX)
        val created = io { content.createGroup(playlistId, id, title) }
        if (created) changed()
        return id.takeIf { created }
    }

    suspend fun renameGroup(playlistId: PlaylistId, id: String, title: String) {
        io { content.renameGroup(playlistId, id, title) }
        changed()
    }

    suspend fun deleteGroup(playlistId: PlaylistId, id: String) {
        io { content.deleteGroup(playlistId, id) }
        changed()
    }

    suspend fun userGroups(playlistId: PlaylistId): List<GroupRow> = io { content.userGroups(playlistId) }

    suspend fun addToGroup(playlistId: PlaylistId, groupId: String, type: ContentType, id: String) {
        io { content.addToGroup(playlistId, groupId, type, id) }
        changed()
    }

    suspend fun removeFromGroup(playlistId: PlaylistId, groupId: String, type: ContentType, id: String) {
        io { content.removeFromGroup(playlistId, groupId, type, id) }
        changed()
    }

    suspend fun groupsHolding(playlistId: PlaylistId, type: ContentType, id: String): List<String> =
        io { content.groupsHolding(playlistId, type, id) }

    suspend fun channelsInUserGroup(playlistId: PlaylistId, groupId: String): List<ChannelRow> =
        io { content.channelsInUserGroup(playlistId, groupId) }

    suspend fun channelNames(playlistId: PlaylistId, ids: List<String>): Map<String, String> = io { content.channelNames(playlistId, ids) }

    suspend fun groupNames(playlistId: PlaylistId, ids: List<String>): Map<String, String> = io { content.groupNames(playlistId, ids) }

    suspend fun libraryTitles(playlistId: PlaylistId, unit: ImportUnit, ids: List<String>): Map<String, String> =
        io { library.titles(playlistId, unit, ids) }

    suspend fun hiddenCount(playlistId: PlaylistId): Long = io { content.hiddenCount(playlistId) }

    suspend fun setLabel(playlistId: PlaylistId, target: CustomisationTarget, id: String, label: String) {
        io { content.setLabel(playlistId, target, id, label) }
        changed()
    }

    suspend fun labels(playlistId: PlaylistId, target: CustomisationTarget): Map<String, String> = io { content.labels(playlistId, target) }

    suspend fun addXtream(name: String?, server: String, username: String, password: String): AddSourceResult =
        adding { service.addXtream(name, server, username, password) }

    suspend fun addM3u(name: String?, url: String): AddSourceResult = adding { service.addM3u(name, url) }

    fun isXtreamPlaylistLink(url: String): Boolean = service.isXtreamPlaylistLink(url)

    /** Saves a custom guide link and downloads the guide from it; returns why the link was refused, or null. */
    suspend fun setGuideLink(playlistId: PlaylistId, url: String): AddSourceFailure? {
        val failure = io { service.setGuideLink(playlistId, url) }
        if (failure == null) {
            changed()
            refreshGuide(playlistId)
        }
        return failure
    }

    suspend fun clearGuideLink(playlistId: PlaylistId) {
        io { service.clearGuideLink(playlistId) }
        changed()
        refreshGuide(playlistId)
    }

    suspend fun hasGuideLink(playlistId: PlaylistId): Boolean = io { content.source(playlistId)?.let(service::hasGuideLink) == true }

    suspend fun addXtreamFromPlaylistLink(name: String?, url: String): AddSourceResult =
        adding { service.addXtreamFromPlaylistLink(name, url) }

    private val mutableAdding = MutableStateFlow(false)

    /** True while a source is being added. The import continues if the form is left; forms refuse a second add meanwhile. */
    val adding: StateFlow<Boolean> = mutableAdding.asStateFlow()

    private suspend fun adding(block: suspend () -> AddSourceResult): AddSourceResult {
        val started = android.os.SystemClock.elapsedRealtime()
        check(mutableAdding.compareAndSet(expect = false, update = true)) { "a source is already being added" }
        // Application scope: leaving the form must not abandon a half-finished import (a retry would add it twice).
        return scope.async {
            val result = try {
                io { block() }
            } finally {
                mutableAdding.value = false
            }
            when (result) {
                is AddSourceResult.Added -> {
                    logImport("live", result.live, started)
                    changed()
                    refreshGuideAndLibrary(result.playlistId)
                }
                is AddSourceResult.Rejected -> Log.i(LOG_TAG, "add rejected: ${result.reason}")
            }
            result
        }.await()
    }

    /** Refreshes channels and then the guide in the background. */
    fun refresh(playlistId: PlaylistId) {
        scope.launch {
            track(playlistId) { it.copy(liveRunning = true) }
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                logImport("live", io { service.refreshLive(playlistId) }, started)
            } finally {
                track(playlistId) { it.copy(liveRunning = false) }
                changed()
            }
            refreshGuideNow(playlistId)
            refreshLibraryNow(playlistId)
        }
    }

    /**
     * Staged background import after a source is added: guide first (Live TV is already usable), then movies, then series, so
     * a large provider's library never delays channels (ROADMAP Phase 8).
     */
    private fun refreshGuideAndLibrary(playlistId: PlaylistId) {
        scope.launch {
            refreshGuideNow(playlistId)
            refreshLibraryNow(playlistId)
        }
    }

    private suspend fun refreshLibraryNow(playlistId: PlaylistId) {
        for (unit in listOf(ImportUnit.MOVIES, ImportUnit.SERIES)) {
            val running: (SourceActivity, Boolean) -> SourceActivity =
                if (unit == ImportUnit.MOVIES) { a, on -> a.copy(moviesRunning = on) } else { a, on -> a.copy(seriesRunning = on) }
            track(playlistId) { running(it, true) }
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                logImport(unit.name.lowercase(), io { service.refreshLibrary(playlistId, unit) }, started)
            } catch (e: Exception) {
                // A failed library import must not break channels or the guide; the unit is marked failed in storage.
                Log.i(LOG_TAG, "${unit.name.lowercase()}: exception ${e::class.simpleName}")
            } finally {
                track(playlistId) { running(it, false) }
                changed()
            }
        }
    }

    fun refreshGuide(playlistId: PlaylistId) {
        scope.launch { refreshGuideNow(playlistId) }
    }

    private suspend fun refreshGuideNow(playlistId: PlaylistId) {
        track(playlistId) { it.copy(guideRunning = true) }
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            logImport("guide", io { service.refreshEpg(playlistId) }, started)
        } catch (e: Exception) {
            // Background imports never take the app down; the unit is marked failed in storage.
            Log.i(LOG_TAG, "guide: exception ${e::class.simpleName}")
        } finally {
            track(playlistId) { it.copy(guideRunning = false) }
            changed()
        }
    }

    suspend fun delete(playlistId: PlaylistId) {
        io { service.deleteSource(playlistId) }
        changed()
    }

    /** Resolves a channel to a playable request, or null when it has no usable stream or its credentials are missing. */
    // --- Library (Phase 8) ---

    suspend fun libraryState(playlistId: PlaylistId, unit: ImportUnit): UnitStateRecord? = io { content.unitState(playlistId, unit) }

    suspend fun libraryGroups(playlistId: PlaylistId, unit: ImportUnit): List<LibraryGroupRow> = io { library.groups(playlistId, unit) }

    suspend fun movies(playlistId: PlaylistId, groupId: String?, limit: Int, offset: Int): List<MovieRow> =
        io { library.movies(playlistId, groupId, limit, offset) }

    suspend fun recentMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> = io { library.recentMovies(playlistId, limit) }

    suspend fun movie(playlistId: PlaylistId, id: String): MovieRow? = io { library.movie(playlistId, id) }

    suspend fun series(playlistId: PlaylistId, groupId: String?, limit: Int, offset: Int): List<SeriesRow> =
        io { library.series(playlistId, groupId, limit, offset) }

    suspend fun seriesById(playlistId: PlaylistId, id: String): SeriesRow? = io { library.seriesById(playlistId, id) }

    suspend fun seasons(playlistId: PlaylistId, seriesId: String): List<SeasonRow> = io { library.seasons(playlistId, seriesId) }

    suspend fun episodes(playlistId: PlaylistId, seriesId: String): List<EpisodeRow> = io { library.episodes(playlistId, seriesId) }

    suspend fun episode(playlistId: PlaylistId, id: String): EpisodeRow? = io { library.episode(playlistId, id) }

    /** Loads a series' seasons and episodes from the provider when needed; returns false when the provider did not answer. */
    suspend fun loadSeriesDetail(playlistId: PlaylistId, seriesId: String): Boolean = io {
        val error = service.loadSeriesDetail(playlistId, seriesId)
        if (error != null) Log.i(LOG_TAG, "series detail: error=${error.code}")
        error == null
    }

    suspend fun setFavorite(type: ContentType, id: String, favorite: Boolean) {
        io { content.setFavorite(type, id, favorite) }
        changed()
    }

    /** "Continue watching" cards with titles and artwork, most recent first (FR-HOME-001). */
    suspend fun continueCards(playlistId: PlaylistId, limit: Int): List<ContinueCard> = io {
        library.continueWatching(playlistId, limit).mapNotNull { item ->
            when (item.type) {
                ContentType.MOVIE -> library.movie(playlistId, item.id)?.let {
                    ContinueCard(item.type, item.id, it.title, null, it.poster, item.progress.fraction)
                }
                ContentType.EPISODE -> library.episode(playlistId, item.id)?.let { episode ->
                    val series = library.seriesById(playlistId, episode.seriesId)
                    ContinueCard(
                        item.type,
                        item.id,
                        series?.title ?: episode.title.orEmpty(),
                        "S${episode.seasonNumber} E${episode.episodeNumber}",
                        series?.poster,
                        item.progress.fraction,
                    )
                }
                else -> null
            }
        }
    }

    /** Local search over the current source (FR-SRCH-001/003): no network, results grouped by type. */
    suspend fun search(playlistId: PlaylistId, query: String, limit: Int = 30): SearchResults = io {
        SearchResults(
            query = query,
            channels = content.searchChannels(playlistId, query, limit),
            movies = library.searchMovies(playlistId, query, limit),
            series = library.searchSeries(playlistId, query, limit),
        )
    }

    suspend fun lastWatchedEpisodeId(seriesId: String): String? = io { library.lastWatchedEpisode(seriesId)?.first }

    suspend fun continueWatching(playlistId: PlaylistId, limit: Int): List<ContinueItem> =
        io { library.continueWatching(playlistId, limit) }

    /** A movie or episode ready to play, resuming from its saved position unless [fromStart]. */
    suspend fun contentPlaybackRequest(playlistId: PlaylistId, type: ContentType, id: String, fromStart: Boolean): PlaybackRequest? = io {
        val resolved = service.resolveContent(playlistId, type, id) as? ResolveResult.Resolved ?: return@io null
        val resume = library.progress(type, id)?.takeIf { !fromStart && !it.completed && it.position.isPositive() }?.position
        PlaybackRequest(resolved.source, PlaybackMode.VOD, startPosition = resume)
    }

    suspend fun saveProgress(
        playlistId: PlaylistId,
        type: ContentType,
        id: String,
        parentId: String?,
        position: Duration,
        duration: Duration?,
        ended: Boolean,
        newSession: Boolean,
    ) {
        io { library.saveProgress(playlistId, type, id, parentId, position, duration, ended, newSession) }
        mutableWatchRevision.update { it + 1 }
    }

    /**
     * Artwork URLs are stored as templates; the few that embed the login (some panels do) are completed from the secret store
     * once per source and kept in memory. Image caches are keyed by the template, never by a URL with credentials.
     */
    suspend fun artworkResolver(playlistId: PlaylistId): (UrlTemplate) -> String? = io {
        val bundle = content.source(playlistId)?.credentialRef?.let { secrets.get(it) }
        return@io { template: UrlTemplate -> template.expand(bundle?.username, bundle?.password)?.unsafeRawValue() }
    }

    suspend fun playbackRequest(playlistId: PlaylistId, channelId: ChannelId): PlaybackRequest? = io {
        when (val resolved = service.resolveChannel(playlistId, channelId)) {
            is ResolveResult.Resolved -> PlaybackRequest(resolved.source, PlaybackMode.LIVE)
            else -> null
        }
    }

    /**
     * Looks up the stream host ahead of a channel switch so the system resolver has it cached (PLAYBACK.md §4 tier T0). Opens
     * no connection. Failures are ignored: playback reports its own errors.
     */
    suspend fun warmUp(request: PlaybackRequest) {
        io {
            val host = runCatching { URI(request.source.url.unsafeRawValue()).host }.getOrNull() ?: return@io
            runCatching { InetAddress.getAllByName(host) }
        }
    }

    suspend fun guideState(playlistId: PlaylistId): UnitStateRecord? = io { content.unitState(playlistId, ImportUnit.EPG) }

    /** Import results for diagnosing real providers on a device: counts, status and codes only — never URLs or logins. */
    private fun logImport(unit: String, outcome: UnitOutcome, startedAtMs: Long) {
        val seconds = (android.os.SystemClock.elapsedRealtime() - startedAtMs) / 1000.0
        Log.i(
            LOG_TAG,
            "$unit: ${outcome.status} items=${outcome.itemCount} error=${outcome.error?.code} in ${"%.1f".format(seconds)} s" +
                (outcome.guide?.let { " guide=$it code=${it.code}" } ?: ""),
        )
    }

    private fun logShortGuide(report: ShortGuideReport) {
        if (report.fetched > 0 || report.skipped != null) Log.i(LOG_TAG, "short guide: $report")
    }

    private fun track(playlistId: PlaylistId, change: (SourceActivity) -> SourceActivity) {
        mutableActivity.update { it + (playlistId to change(it[playlistId] ?: SourceActivity())) }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val LOG_TAG = "IptvImport"

        /** Calls for more channels than this (whole lists) use the stored guide only. */
        const val SHORT_GUIDE_MAX_CHANNELS = 20
        const val KEY_CURRENT_SOURCE = "current_source"
    }
}

/** How many channels one stored-guide query covers; the whole list is read in batches of this size. */
private const val GUIDE_BATCH = 500

/** Base for the id a new group gets from the clock: short, and unique enough for one viewer making groups by hand. */
private const val RADIX = 36

/** Preference key for the Home row choice. */
private const val HOME_ROWS = "home.rows"
