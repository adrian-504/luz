package app.iptvplayer.tv.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.core.content.edit
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.ingestion.AddSourceFailure
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.ingestion.EpisodeArt
import app.iptvplayer.ingestion.PersonArt
import app.iptvplayer.ingestion.ShortGuideReport
import app.iptvplayer.ingestion.SourceService
import app.iptvplayer.ingestion.TitleArt
import app.iptvplayer.ingestion.TmdbService
import app.iptvplayer.ingestion.UnitOutcome
import app.iptvplayer.platform.AndroidPlatformCapabilities
import app.iptvplayer.platform.SystemClock
import app.iptvplayer.platform.net.OkHttpTransport
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.platform.secrets.KeystoreSecretStore
import app.iptvplayer.protocols.media.ResolveResult
import app.iptvplayer.protocols.tmdb.TmdbClient
import app.iptvplayer.storage.ArrangedGroup
import app.iptvplayer.storage.BundledSqliteDriver
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.ContinueItem
import app.iptvplayer.storage.DetailCoverage
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.storage.GroupRow
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.storage.LibraryGroupRow
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.MovieVersionRow
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.storage.PersonHit
import app.iptvplayer.storage.SeasonRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.storage.SourceRecord
import app.iptvplayer.storage.TitleDetailRow
import app.iptvplayer.storage.UnitStateRecord
import app.iptvplayer.storage.db.IptvDatabase
import app.iptvplayer.tv.developer.DeveloperStreams
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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class SearchResults(
    val query: String,
    val channels: List<ChannelRow>,
    val movies: List<MovieRow>,
    val series: List<SeriesRow>,
    val people: List<PersonHit> = emptyList(),
)

/** "Because you watched …": the film it started from and what shares its director, cast or genres. */
data class Recommendation(val seed: MovieRow, val movies: List<MovieRow>)

/** The shelves under a title's page: its director's other titles (named by [director]) and titles like it. */
data class TitleShelves<T>(val director: String?, val byDirector: List<T>, val moreLikeThis: List<T>)

/** A movie or episode on Home's "Continue watching" row. */
data class ContinueCard(
    val type: ContentType,
    val id: String,
    val title: String,
    val subtitle: String?,
    val poster: UrlTemplate?,
    val fraction: Float?,
    /** The wide picture, for the landscape card; the poster when there is none. */
    val backdrop: UrlTemplate? = null,
    /** How much is left to watch, when the length is known. */
    val remaining: kotlin.time.Duration? = null,
)

/** A programme on Home's "Coming up" row: what, when, and the channel to jump to. */
data class ComingUp(val channel: ChannelRow, val programme: GuideProgramme)

/** "Because you watch …": the genre the viewer watches most, and films of it they have not seen. */
data class GenrePick(val genre: String, val movies: List<MovieRow>)

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

    private val tmdb by lazy {
        TmdbService(OkHttpTransport(::networkAvailable), secrets, library, SystemClock) {
            DeveloperStreams.tmdbBase(appContext) ?: TmdbClient.BASE_URL
        }
    }

    private val mutableListRevision = MutableStateFlow(0)

    /** Increments when TMDB lists are read or removed, so the rows built from them reload (ADR-0038). */
    val listRevision: StateFlow<Int> = mutableListRevision.asStateFlow()

    private val mutableRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    /**
     * Increments as film and show pages arrive in the background. Separate from [revision] on purpose: pages arrive for
     * hours on a large library, and only the screens built from them (Home, Movies, Series) should reload — not the
     * channel list, and never a player that is preparing its next channel.
     */
    private val mutableDetailRevision = MutableStateFlow(0)
    val detailRevision: StateFlow<Int> = mutableDetailRevision.asStateFlow()

    /** Set while a player is open; the background fetch waits so it never competes with a stream. */
    @Volatile
    var playing: Boolean = false

    private var enrichment: kotlinx.coroutines.Job? = null

    private val mutableDetailFetchRunning = MutableStateFlow(false)

    /** True while film pages are being fetched in the background, so screens built from them can say more are coming. */
    val detailFetchRunning: StateFlow<Boolean> = mutableDetailFetchRunning.asStateFlow()

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

    suspend fun pin(playlistId: PlaylistId, target: CustomisationTarget, id: String, pinned: Boolean) {
        io { if (pinned) content.pin(playlistId, target, id) else content.unpin(playlistId, target, id) }
        changed()
    }

    suspend fun groupsToArrange(playlistId: PlaylistId): List<ArrangedGroup> = io { content.groupsToArrange(playlistId) }

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

    /** Every row Home had when the viewer saved their choice, so rows added later can be told apart from rows turned off. */
    suspend fun homeRowsKnown(): List<String>? = io {
        content.preference(HOME_ROWS_KNOWN)?.split(",")?.filter { it.isNotBlank() }
    }

    suspend fun setHomeRows(rows: List<String>?, known: List<String>) {
        io {
            content.setPreference(HOME_ROWS, rows?.joinToString(","))
            content.setPreference(HOME_ROWS_KNOWN, known.joinToString(","))
        }
        changed()
    }

    /** The viewer's own groups: making one, filling it, and what is in it (FR-PLM-001, FR-FAV-002). */
    suspend fun createGroup(playlistId: PlaylistId, title: String): String? {
        val id = "ugrp_" + SystemClock.now().toEpochMilliseconds().toString(RADIX)
        val created = io { content.createGroup(playlistId, id, title) }
        if (created) changed()
        return id.takeIf { created }
    }

    /**
     * Makes a group and puts one title in it. Runs on Luz's own scope, not the screen's: the menu that asks for the name
     * closes as soon as it is confirmed, and closing it used to cancel the second step, leaving an empty group.
     */
    fun createGroupWith(playlistId: PlaylistId, title: String, type: ContentType, contentId: String) {
        scope.launch { createGroup(playlistId, title)?.let { addToGroup(playlistId, it, type, contentId) } }
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

    /**
     * Fetches film pages in the background for the whole library, newest first and slowly (ADR-0035): at most one request
     * every [ENRICHMENT_PAUSE], waiting while something plays, and stopping at the first refusal. Only one runs at a time;
     * calling it again while one runs does nothing.
     */
    // --- TMDB (ADR-0038) ---

    data class TmdbStatus(val hasKey: Boolean, val fetchedAt: Instant?, val titles: Long, val error: String?)

    private val mutableTmdbError = MutableStateFlow<String?>(null)

    suspend fun tmdbStatus(): TmdbStatus = io {
        TmdbStatus(tmdb.hasKey(), library.listsFetchedAt(), library.listCounts().values.sum(), mutableTmdbError.value)
    }

    /** Checks and keeps the viewer's key, then reads the lists. Returns the error code when TMDB did not accept it. */
    suspend fun setTmdbKey(raw: String): String? {
        val error = io { tmdb.setKey(raw) }
        if (error != null) {
            Log.i(LOG_TAG, "tmdb: key not accepted (${error.code})")
            return error.code
        }
        refreshTmdb(force = true)
        return null
    }

    suspend fun removeTmdbKey() {
        io { tmdb.removeKey() }
        mutableTmdbError.value = null
        mutableListRevision.update { it + 1 }
    }

    /** Reads the TMDB lists when a key is kept and they are more than a day old ([force]: now). */
    fun refreshTmdb(force: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            val error = runCatching { tmdb.refresh(force) }.getOrElse { e ->
                Log.i(LOG_TAG, "tmdb: exception ${e::class.simpleName}")
                null
            }
            mutableTmdbError.value = error?.code
            if (error != null) Log.i(LOG_TAG, "tmdb: refresh failed (${error.code})")
            mutableListRevision.update { it + 1 }
        }
    }

    /** Whether titles and people the viewer opens may be asked about at TMDB for artwork (ADR-0039); on by default. */
    val tmdbArtwork: Boolean get() = preferences.getBoolean(KEY_TMDB_ARTWORK, true)

    suspend fun setTmdbArtwork(on: Boolean) {
        io { preferences.edit(commit = true) { putBoolean(KEY_TMDB_ARTWORK, on) } }
    }

    /** The title logo of an opened film or show; null when switched off, without a key, or when TMDB has none. */
    suspend fun titleArt(type: ContentType, title: String, year: Int?, tmdbId: String?, ask: Boolean = true): TitleArt? {
        if (!tmdbArtwork) return null
        return io { runCatching { tmdb.artwork(type, title, year, tmdbId, ask) }.getOrNull() }
    }

    /** Portraits of [names] already learned from TMDB; nothing is asked. */
    suspend fun portraits(names: Collection<String>): Map<String, String> = if (!tmdbArtwork) emptyMap() else io { tmdb.portraits(names) }

    /** Episode names, pictures and descriptions from TMDB for one season; empty when switched off or without a key. */
    suspend fun episodeArt(title: String, year: Int?, tmdbId: String?, season: Int): Map<Int, EpisodeArt> {
        if (!tmdbArtwork) return emptyMap()
        return io { runCatching { tmdb.episodes(title, year, tmdbId, season) }.getOrDefault(emptyMap()) }
    }

    suspend fun personArt(name: String): PersonArt? {
        if (!tmdbArtwork) return null
        return io { runCatching { tmdb.person(name) }.getOrNull() }
    }

    suspend fun moviesOfList(playlistId: PlaylistId, list: ExternalList, limit: Int): List<MovieRow> =
        io { library.moviesOfList(playlistId, list.name, limit) }

    suspend fun seriesOfList(playlistId: PlaylistId, list: ExternalList, limit: Int): List<SeriesRow> =
        io { library.seriesOfList(playlistId, list.name, limit) }

    private var freshness: kotlinx.coroutines.Job? = null

    /**
     * Keeps a source current without the viewer pressing Refresh: now, and every hour while Luz stays open (a television
     * app can stay open for days), the guide is downloaded again when it is more than [GUIDE_MAX_AGE] old — providers'
     * guides often cover only the next few hours — and channels, films and shows when they are more than [LIBRARY_MAX_AGE]
     * old. Nothing starts while something plays or an import is already running.
     */
    fun keepFresh(playlistId: PlaylistId) {
        if (freshness?.isActive == true) return
        freshness = scope.launch {
            while (true) {
                val busy = playing || mutableActivity.value[playlistId]?.let {
                    it.liveRunning || it.guideRunning || it.moviesRunning || it.seriesRunning
                } == true
                if (!busy) {
                    val now = SystemClock.now()
                    fun stale(unit: ImportUnit, maxAge: kotlin.time.Duration): Boolean {
                        val finished = content.unitState(playlistId, unit)?.finishedAt ?: return true
                        return now - finished > maxAge
                    }
                    val library = io { listOf(ImportUnit.LIVE, ImportUnit.MOVIES, ImportUnit.SERIES).any { stale(it, LIBRARY_MAX_AGE) } }
                    val guide = io { stale(ImportUnit.EPG, GUIDE_MAX_AGE) }
                    when {
                        library -> refresh(playlistId).also { Log.i(LOG_TAG, "fresh: refreshing everything") }
                        guide -> refreshGuide(playlistId).also { Log.i(LOG_TAG, "fresh: refreshing the guide") }
                    }
                }
                kotlinx.coroutines.delay(FRESHNESS_CHECK)
            }
        }
    }

    fun startDetailFetch(playlistId: PlaylistId) {
        if (enrichment?.isActive == true) return
        enrichment = scope.launch(Dispatchers.IO) {
            mutableDetailFetchRunning.value = true
            try {
                fetchDetails(playlistId)
            } finally {
                mutableDetailFetchRunning.value = false
            }
        }
    }

    private suspend fun fetchDetails(playlistId: PlaylistId) {
        while (true) {
            while (detailFetchShouldWait()) kotlinx.coroutines.delay(ENRICHMENT_PAUSE * 10)
            val stored = runCatching {
                service.enrichMovieDetails(playlistId, ENRICHMENT_CHUNK, ENRICHMENT_PAUSE, keepGoing = { !detailFetchShouldWait() }) {
                    mutableDetailRevision.update { it + 1 }
                }
            }.getOrElse { e ->
                Log.i(LOG_TAG, "details: exception ${e::class.simpleName}")
                0
            }
            Log.i(LOG_TAG, "details: stored=$stored")
            // Stopped because something started playing: carry on once it ends. Otherwise it finished or was refused.
            if (!detailFetchShouldWait()) return
        }
    }

    /**
     * The background fetch steps aside while something plays and while any import runs: a provider serving a large
     * download (a whole library, a guide) can drop it when a stream of page requests arrives at the same time.
     */
    private fun detailFetchShouldWait(): Boolean =
        playing || mutableActivity.value.values.any { it.liveRunning || it.guideRunning || it.moviesRunning || it.seriesRunning }

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
        startDetailFetch(playlistId)
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

    // --- Details, shelves and people (ADR-0035) ---

    /** Fetches a film's page when it has none; the page is read with [detail]. False when the provider did not answer. */
    suspend fun loadMovieDetail(playlistId: PlaylistId, movieId: String): Boolean = io {
        val error = service.loadMovieDetail(playlistId, movieId)
        if (error != null) Log.i(LOG_TAG, "movie detail: error=${error.code}")
        error == null
    }

    suspend fun detail(playlistId: PlaylistId, type: ContentType, id: String): TitleDetailRow? = io { library.detail(playlistId, type, id) }

    suspend fun detailCoverage(playlistId: PlaylistId): List<DetailCoverage> = io { library.detailCoverage(playlistId) }

    suspend fun movieVersions(playlistId: PlaylistId, id: String): List<MovieVersionRow> = io { library.movieVersions(playlistId, id) }

    suspend fun topRatedMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> = io { library.topRatedMovies(playlistId, limit) }

    suspend fun popularMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> = io { library.popularMovies(playlistId, limit) }

    suspend fun moviesOfGenre(playlistId: PlaylistId, genre: String, limit: Int, offset: Int = 0): List<MovieRow> =
        io { library.moviesOfGenre(playlistId, genre, limit, offset) }

    suspend fun movieGenres(playlistId: PlaylistId, limit: Int): List<Pair<String, Long>> = io { library.movieGenres(playlistId, limit) }

    suspend fun moviesOfDecade(playlistId: PlaylistId, decade: Int, limit: Int, offset: Int = 0): List<MovieRow> =
        io { library.moviesOfDecade(playlistId, decade, limit, offset) }

    suspend fun movieDecades(playlistId: PlaylistId): List<Pair<Int, Long>> = io { library.movieDecades(playlistId) }

    suspend fun moviesOfLength(playlistId: PlaylistId, from: Duration, to: Duration, limit: Int): List<MovieRow> =
        io { library.moviesOfLength(playlistId, from, to, limit) }

    suspend fun favoriteMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> = io { library.favoriteMovies(playlistId, limit) }

    suspend fun newEpisodeSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> = io { library.newEpisodeSeries(playlistId, limit) }

    suspend fun topRatedSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> = io { library.topRatedSeries(playlistId, limit) }

    suspend fun popularSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> = io { library.popularSeries(playlistId, limit) }

    suspend fun seriesOfGenre(playlistId: PlaylistId, genre: String, limit: Int, offset: Int = 0): List<SeriesRow> =
        io { library.seriesOfGenre(playlistId, genre, limit, offset) }

    suspend fun seriesGenres(playlistId: PlaylistId, limit: Int): List<Pair<String, Long>> = io { library.seriesGenres(playlistId, limit) }

    suspend fun favoriteSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> = io { library.favoriteSeries(playlistId, limit) }

    suspend fun titlesOfPerson(playlistId: PlaylistId, name: String): Pair<List<MovieRow>, List<SeriesRow>> =
        io { library.titlesOfPerson(playlistId, name) }

    /**
     * "Because you watched …" (ADR-0035): starting from the films watched most recently, the first one with a page gives
     * the recommendations — its director counts most, then its leading cast, then its genres — leaving out anything
     * already watched. Null until at least [MIN_RECOMMENDATIONS] films share something with one of them.
     */
    suspend fun becauseYouWatched(playlistId: PlaylistId, limit: Int): Recommendation? = io {
        val watched = library.recentlyWatchedMovieIds(playlistId, WATCH_HISTORY).toSet()
        for (seedId in watched) {
            val seed = library.movie(playlistId, seedId) ?: continue
            val page = library.detail(playlistId, ContentType.MOVIE, seedId) ?: continue
            val picks = similarMovies(playlistId, page, watched, limit, withDirectors = true)
            if (picks.size >= MIN_RECOMMENDATIONS) return@io Recommendation(seed, picks)
        }
        null
    }

    /**
     * What a film's page offers under it: the director's other films, newest first, and "More like this" — films sharing
     * its leading cast and genres, most in common first — with nothing repeated between the two and neither the film
     * nor its other versions ([exclude]). Read from the library only.
     */
    suspend fun movieShelves(playlistId: PlaylistId, movieId: String, exclude: Set<String>, limit: Int): TitleShelves<MovieRow> = io {
        val page = library.detail(playlistId, ContentType.MOVIE, movieId) ?: return@io TitleShelves(null, emptyList(), emptyList())
        val skip = exclude + movieId
        val director = page.directors.firstOrNull()
        val byDirector = director?.let { library.titlesOfPerson(playlistId, it).first }.orEmpty()
            .filter { it.id !in skip }
            .sortedByDescending { it.year ?: 0 }
            .take(limit)
        val similar = similarMovies(playlistId, page, skip + byDirector.map { it.id }, limit, withDirectors = false)
        TitleShelves(director.takeIf { byDirector.isNotEmpty() }, byDirector, similar)
    }

    /** "More like this" for a show: shows sharing its leading cast and genres, most in common first. */
    suspend fun seriesShelves(playlistId: PlaylistId, seriesId: String, limit: Int): List<SeriesRow> = io {
        val page = library.detail(playlistId, ContentType.SERIES, seriesId) ?: return@io emptyList()
        val scores = HashMap<String, Int>()
        val rows = HashMap<String, SeriesRow>()
        fun score(shows: List<SeriesRow>, points: Int) = shows.forEach { show ->
            rows[show.id] = show
            scores.merge(show.id, points, Int::plus)
        }
        page.cast.take(LEADING_CAST).forEach { score(library.titlesOfPerson(playlistId, it).second, CAST_POINTS) }
        page.genres.forEach { score(library.seriesOfGenre(playlistId, it, GENRE_CANDIDATES), GENRE_POINTS) }
        scores.keys.filter { it != seriesId }
            .sortedWith(compareByDescending<String> { scores.getValue(it) }.thenByDescending { rows.getValue(it).lastModifiedAt })
            .take(limit)
            .map { rows.getValue(it) }
            .takeIf { it.size >= MIN_RECOMMENDATIONS }
            .orEmpty()
    }

    private fun similarMovies(
        playlistId: PlaylistId,
        page: TitleDetailRow,
        exclude: Set<String>,
        limit: Int,
        withDirectors: Boolean,
    ): List<MovieRow> {
        val scores = HashMap<String, Int>()
        val rows = HashMap<String, MovieRow>()
        fun score(movies: List<MovieRow>, points: Int) = movies.forEach { movie ->
            rows[movie.id] = movie
            scores.merge(movie.id, points, Int::plus)
        }
        if (withDirectors) page.directors.forEach { score(library.titlesOfPerson(playlistId, it).first, DIRECTOR_POINTS) }
        page.cast.take(LEADING_CAST).forEach { score(library.titlesOfPerson(playlistId, it).first, CAST_POINTS) }
        page.genres.forEach { score(library.moviesOfGenre(playlistId, it, GENRE_CANDIDATES), GENRE_POINTS) }
        // A film from the same years is a better neighbour than one from another era.
        page.year?.let { year ->
            rows.values.filter {
                it.year != null && kotlin.math.abs(it.year!! - year) <= NEAR_YEARS
            }.forEach { scores.merge(it.id, 1, Int::plus) }
        }
        return scores.keys.filter { it !in exclude }
            .sortedWith(compareByDescending<String> { scores.getValue(it) }.thenByDescending { rows.getValue(it).addedAt })
            .take(limit)
            .map { rows.getValue(it) }
    }

    suspend fun moviesInUserGroup(playlistId: PlaylistId, groupId: String, limit: Int): List<MovieRow> =
        io { library.moviesInUserGroup(playlistId, groupId, limit) }

    suspend fun seriesInUserGroup(playlistId: PlaylistId, groupId: String, limit: Int): List<SeriesRow> =
        io { library.seriesInUserGroup(playlistId, groupId, limit) }

    suspend fun recordChannelWatch(playlistId: PlaylistId, channelId: ChannelId) = io {
        library.recordChannelWatch(playlistId, channelId.value)
    }

    /**
     * Programmes about to start, or just started, on the viewer's own channels (favourites, then the most watched): from
     * ten minutes ago to three hours ahead, soonest first, at most two per channel. Stored guide only — Home asks the
     * provider nothing for it. Empty for a viewer with no channels of their own yet.
     */
    suspend fun comingUp(playlistId: PlaylistId, limit: Int, now: Instant): List<ComingUp> = io {
        val watched = content.channelsByIds(playlistId, library.mostWatchedChannelIds(playlistId, COMING_UP_CHANNELS))
        val channels = (content.favoriteChannels(playlistId) + watched).distinctBy { it.id }.take(COMING_UP_CHANNELS)
        if (channels.isEmpty()) return@io emptyList()
        val byId = channels.associateBy { it.id.value }
        val from = now - JUST_STARTED
        epg.programmes(playlistId.value, channels.map { it.id.value }, from, now + COMING_UP_AHEAD)
            .flatMap { (channelId, programmes) ->
                programmes.filter { it.start >= from && it.start <= now + COMING_UP_AHEAD }.take(2).mapNotNull { programme ->
                    byId[channelId]?.let { ComingUp(it, programme) }
                }
            }
            .sortedBy { it.programme.start }
            .take(limit)
    }

    /**
     * The genre of most of the films the viewer watched lately (at least two of them), and the best-rated films of it
     * they have not watched. Worked out on the television from the watch history and the films' pages.
     */
    suspend fun genrePick(playlistId: PlaylistId, limit: Int): GenrePick? = io {
        val watched = library.recentlyWatchedMovieIds(playlistId, GENRE_HISTORY)
        val genre = watched.flatMap { library.detail(playlistId, ContentType.MOVIE, it)?.genres.orEmpty() }
            .groupingBy { it }.eachCount()
            .filterValues { it >= MIN_GENRE_WATCHED }
            .maxByOrNull { it.value }?.key ?: return@io null
        val seen = watched.toSet()
        val movies = library.moviesOfGenre(playlistId, genre, GENRE_CANDIDATES * 2)
            .filter { it.id !in seen }
            .sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }
            .take(limit)
        GenrePick(genre, movies).takeIf { movies.size >= MIN_RECOMMENDATIONS }
    }

    /** Channels the viewer watches most, falling back to favourites when they have not watched any yet. */
    suspend fun mostWatchedChannels(playlistId: PlaylistId, limit: Int): List<ChannelRow> = io {
        val ids = library.mostWatchedChannelIds(playlistId, limit)
        if (ids.isEmpty()) return@io content.favoriteChannels(playlistId).take(limit)
        content.channelsByIds(playlistId, ids)
    }

    /** "Continue watching" cards with titles and artwork, most recent first (FR-HOME-001). */
    suspend fun continueCards(playlistId: PlaylistId, limit: Int): List<ContinueCard> = io {
        library.continueWatching(playlistId, limit).mapNotNull { item ->
            when (item.type) {
                ContentType.MOVIE -> library.movie(playlistId, item.id)?.let {
                    ContinueCard(
                        item.type,
                        item.id,
                        it.title,
                        null,
                        it.poster,
                        item.progress.fraction,
                        it.backdrop,
                        item.progress.remaining,
                    )
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
                        series?.backdrop,
                        item.progress.remaining,
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
            people = library.searchPeople(playlistId, query, PEOPLE_LIMIT),
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
        const val KEY_TMDB_ARTWORK = "tmdb_artwork"

        /** One film page per this pause at most, so a whole library never looks like a flood to a provider. */
        val ENRICHMENT_PAUSE = 400.milliseconds
        const val ENRICHMENT_CHUNK = 100_000
        const val PEOPLE_LIMIT = 12
        val GUIDE_MAX_AGE = 6.hours
        val LIBRARY_MAX_AGE = 24.hours
        val FRESHNESS_CHECK = 1.hours
        const val WATCH_HISTORY = 10
        const val LEADING_CAST = 4
        const val GENRE_CANDIDATES = 60
        const val DIRECTOR_POINTS = 5
        const val CAST_POINTS = 3
        const val GENRE_POINTS = 1
        const val MIN_RECOMMENDATIONS = 4
        const val COMING_UP_CHANNELS = 40
        val JUST_STARTED = 10.minutes
        val COMING_UP_AHEAD = 3.hours
        const val GENRE_HISTORY = 30
        const val MIN_GENRE_WATCHED = 2
        const val NEAR_YEARS = 8
    }
}

/** How many channels one stored-guide query covers; the whole list is read in batches of this size. */
private const val GUIDE_BATCH = 500

/** Base for the id a new group gets from the clock: short, and unique enough for one viewer making groups by hand. */
private const val RADIX = 36

/** Preference key for the Home row choice. */
private const val HOME_ROWS = "home.rows"
private const val HOME_ROWS_KNOWN = "home.rows.known"
