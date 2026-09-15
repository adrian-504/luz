package app.iptvplayer.tv.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.ingestion.SourceService
import app.iptvplayer.platform.AndroidPlatformCapabilities
import app.iptvplayer.platform.SystemClock
import app.iptvplayer.platform.net.OkHttpTransport
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.platform.secrets.KeystoreSecretStore
import app.iptvplayer.protocols.media.ResolveResult
import app.iptvplayer.storage.BundledSqliteDriver
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.storage.GroupRow
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.storage.SourceRecord
import app.iptvplayer.storage.db.IptvDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.URI
import kotlin.time.Instant

/** Import activity per source, for the UI. */
data class SourceActivity(val liveRunning: Boolean = false, val guideRunning: Boolean = false)

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
    private val content by lazy { ContentStore(driver, SystemClock) }
    private val epg by lazy { EpgStore(driver) }
    private val service by lazy {
        SourceService(
            OkHttpTransport(::networkAvailable),
            KeystoreSecretStore(appContext),
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

    suspend fun nowNext(playlistId: PlaylistId, channels: List<ChannelId>, now: Instant): Map<String, NowNextRow> =
        io { epg.nowNext(playlistId.value, channels.map { it.value }, now) }

    suspend fun programmes(
        playlistId: PlaylistId,
        channels: List<ChannelId>,
        from: Instant,
        until: Instant,
    ): Map<String, List<GuideProgramme>> = io { epg.programmes(playlistId.value, channels.map { it.value }, from, until) }

    suspend fun setFavorite(channelId: ChannelId, favorite: Boolean) {
        io { content.setFavorite(channelId, favorite) }
        changed()
    }

    suspend fun addXtream(name: String?, server: String, username: String, password: String): AddSourceResult =
        adding { service.addXtream(name, server, username, password) }

    suspend fun addM3u(name: String?, url: String): AddSourceResult = adding { service.addM3u(name, url) }

    private suspend fun adding(block: suspend () -> AddSourceResult): AddSourceResult {
        val result = io { block() }
        if (result is AddSourceResult.Added) {
            changed()
            refreshGuide(result.playlistId)
        }
        return result
    }

    /** Refreshes channels and then the guide in the background. */
    fun refresh(playlistId: PlaylistId) {
        scope.launch {
            track(playlistId) { it.copy(liveRunning = true) }
            try {
                io { service.refreshLive(playlistId) }
            } finally {
                track(playlistId) { it.copy(liveRunning = false) }
                changed()
            }
            refreshGuideNow(playlistId)
        }
    }

    fun refreshGuide(playlistId: PlaylistId) {
        scope.launch { refreshGuideNow(playlistId) }
    }

    private suspend fun refreshGuideNow(playlistId: PlaylistId) {
        track(playlistId) { it.copy(guideRunning = true) }
        try {
            io { service.refreshEpg(playlistId) }
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

    private fun track(playlistId: PlaylistId, change: (SourceActivity) -> SourceActivity) {
        mutableActivity.update { it + (playlistId to change(it[playlistId] ?: SourceActivity())) }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val KEY_CURRENT_SOURCE = "current_source"
    }
}
