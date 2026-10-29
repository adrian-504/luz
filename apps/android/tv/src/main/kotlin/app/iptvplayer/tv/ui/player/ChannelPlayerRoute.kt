package app.iptvplayer.tv.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.live.ViewingSession
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.playback.ChannelHistory
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.domain.playback.PreparationWindow
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.companion.CompanionLibrary
import app.iptvplayer.tv.ui.library.rememberArtworkResolver
import app.iptvplayer.tv.ui.live.ChannelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Plays a channel from a list and zaps through that list (PLAYBACK.md §4, DESIGN_SYSTEM.md §6): the channel name changes
 * at once on each key press, and the stream is resolved and prepared only after the keys settle, so fast zapping opens one
 * stream instead of one per press. While a channel plays, its likely successors (next/previous in the zap direction and
 * the last channel) are resolved ahead and their hosts looked up (tier T0: no stream is opened), so the switch itself only
 * starts the player. Resolved streams stay in memory only and are dropped when the channel data changes.
 */
@Composable
fun ChannelPlayerRoute(playlistId: PlaylistId, scope: ChannelScope, startChannel: ChannelId) {
    val graph = LocalAppGraph.current
    // The background fetch of film pages waits while anything plays, so it never competes with the stream.
    DisposableEffect(Unit) {
        graph.playing = true
        onDispose { graph.playing = false }
    }
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    var channels by remember { mutableStateOf<List<ChannelRow>?>(null) }
    var target by rememberSaveable { mutableStateOf(startChannel.value) }
    var request by remember { mutableStateOf<PlaybackRequest?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var guide by remember { mutableStateOf<NowNextRow?>(null) }
    var listGuide by remember { mutableStateOf<Map<String, NowNextRow>>(emptyMap()) }
    var direction by remember { mutableIntStateOf(0) }
    var intentAtMs by remember { mutableStateOf<Long?>(null) }
    val history = remember { ChannelHistory<String>() }
    var lastChannel by remember { mutableStateOf<String?>(null) }
    var playingChannel by remember { mutableStateOf<String?>(null) }
    val prepared = remember { mutableMapOf<String, PlaybackRequest>() }
    // The copies of the channel on screen, best first, and which of them this attempt has tried (ADR-0052).
    var copies by remember { mutableStateOf<List<ChannelId>>(emptyList()) }
    val tried = remember { mutableSetOf<String>() }
    var playingCopy by remember { mutableStateOf<String?>(null) }
    var recordedOk by remember { mutableStateOf<String?>(null) }
    // The copy that failed and the one playing instead, for the live bar's sentence (ADR-0052).
    var fellBack by remember { mutableStateOf<Pair<String, String>?>(null) }
    val note = fellBack?.let { (from, to) -> stringResource(R.string.bar_copy_fallback, from, to) }
    var favouritesList by remember { mutableStateOf(false) }
    var playingQuality by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playingCopy) { playingQuality = playingCopy?.let { graph.channelRow(playlistId, ChannelId(it))?.quality } }
    var groupName by remember { mutableStateOf<String?>(null) }
    var bannerMs by remember { mutableLongStateOf(DEFAULT_BAR_SECONDS * MS_PER_SECOND) }
    // The viewing log (round 3, for round 8): one session per channel watched for a minute or more.
    val session = remember { mutableStateOf<Pair<String, ViewingSession>?>(null) }
    fun endSession() {
        val (id, current) = session.value ?: return
        session.value = null
        val seconds = current.end(SystemClock.elapsedRealtime()) ?: return
        graph.scope.launch { graph.recordSession(playlistId, ContentType.CHANNEL, id, null, current.startedAt, seconds) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // A TV switched off stops the activity without leaving the player: the session ends there too.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) endSession() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            endSession()
        }
    }

    LaunchedEffect(Unit) {
        bannerMs = (graph.preference(LIVE_BAR_SECONDS)?.toLongOrNull() ?: DEFAULT_BAR_SECONDS) * MS_PER_SECOND
    }
    LaunchedEffect(revision) {
        prepared.clear()
        val scoped = when (scope) {
            ChannelScope.All -> graph.channels(playlistId, null)
            ChannelScope.Favorites -> graph.favoriteChannels(playlistId)
            is ChannelScope.Group -> graph.channels(playlistId, scope.id)
            // Zapping stays inside the list the viewer came from, their own groups included.
            is ChannelScope.Mine -> graph.channelsInUserGroup(playlistId, scope.id)
        }
        // A list that is gone or emptied falls back to all channels (x4).
        channels = scoped.ifEmpty { graph.channels(playlistId, null) }
        groupName = when {
            scoped.isEmpty() -> null
            scope is ChannelScope.Group -> graph.groups(playlistId).firstOrNull { it.id == scope.id }?.title
            scope is ChannelScope.Mine -> graph.userGroups(playlistId).firstOrNull { it.id == scope.id }?.title
            else -> null
        }
        favouritesList = scoped.isNotEmpty() && scope == ChannelScope.Favorites
    }
    val list = channels ?: return
    val index = list.indexOfFirst { it.id.value == target }.coerceAtLeast(0)
    val channel = list.getOrNull(index) ?: return

    LaunchedEffect(target) {
        delay(ZAP_SETTLE_MS)
        val ahead = prepared.remove(channel.id.value)
        val resolved = ahead ?: graph.playbackRequest(playlistId, channel.id)
        unavailable = resolved == null
        request = resolved?.copy(intentAtMs = intentAtMs, prepared = ahead != null)
        tried.clear()
        tried += channel.id.value
        playingCopy = channel.id.value
        fellBack = null
        endSession()
        session.value = channel.id.value to ViewingSession(System.currentTimeMillis(), graph.sessionMinSeconds)
        history.played(channel.id.value)
        graph.recordChannelWatch(playlistId, channel.id)
        // For opening Luz on this channel next time, in the list it came from (3.9).
        graph.setPreference(LAST_CHANNEL_SCOPE, scope.key())
        copies = graph.channelCopies(playlistId, channel.id)
        lastChannel = history.last
        playingChannel = history.current
        guide = graph.nowNext(playlistId, listOf(channel.id), Clock.System.now())[channel.id.value]
        subtitle = guide?.current?.title

        // T0 preparation for the next switch; cancelled by the next key press (this effect restarts).
        val candidates = PreparationWindow.candidates(list.map { it.id.value }, index, direction, history.last)
        prepared.keys.retainAll(candidates.toSet())
        for (id in candidates) {
            if (id in prepared) continue
            val next = graph.playbackRequest(playlistId, ChannelId(id)) ?: continue
            prepared[id] = next
            graph.warmUp(next)
        }
    }

    // What is on around the current channel, for the channel list over the picture; the stored guide only, and only the
    // part of a list of thousands the viewer is likely to scroll to.
    LaunchedEffect(index, revision) {
        val around = list.subList((index - GUIDE_AROUND).coerceAtLeast(0), (index + GUIDE_AROUND).coerceAtMost(list.size))
        listGuide = listGuide + graph.storedNowNext(playlistId, around.filter { it.id.value !in listGuide }, Clock.System.now())
    }
    val resolver = rememberArtworkResolver(playlistId)
    PlayerScreen(
        request = request,
        live = LiveInfo(
            channel.number,
            channel.label,
            channel.logo,
            guide?.current,
            guide?.next,
            if (favouritesList) stringResource(R.string.live_favorites) else groupName,
            note,
        ),
        bannerMs = bannerMs,
        // The playing copy's own quality on the live bar (3.11), never SD.
        badges = listOfNotNull(playingQuality?.takeIf { it != "SD" }),
        onState = { state, firstFrameMs ->
            val copy = playingCopy
            if (state == PlaybackState.PLAYING && copy != null && recordedOk != copy) {
                recordedOk = copy
                coroutines.launch { graph.recordPlayback(playlistId, ChannelId(copy), ok = true, firstFrameMs = firstFrameMs) }
            }
            val now = SystemClock.elapsedRealtime()
            session.value?.second?.let { if (state == PlaybackState.PLAYING) it.play(now) else it.hold(now) }
        },
        // A copy that does not start: the next one, quietly, and the live bar says why (ADR-0052).
        onFailed = {
            val failed = playingCopy
            failed?.let { coroutines.launch { graph.recordPlayback(playlistId, ChannelId(it), ok = false) } }
            val next = copies.firstOrNull { it.value !in tried }
            if (next == null) {
                false
            } else {
                tried += next.value
                playingCopy = next.value
                coroutines.launch {
                    val resolved = graph.playbackRequest(playlistId, next)
                    if (resolved == null) {
                        unavailable = true
                        return@launch
                    }
                    request = resolved
                    val from = failed?.let { graph.channelRow(playlistId, ChannelId(it))?.quality }
                    val to = graph.channelRow(playlistId, next)?.quality
                    fellBack = if (from != null && to != null) from to to else null
                }
                true
            }
        },
        channels = list.map { ChannelChoice(it.id.value, it.number, it.label, it.logo, listGuide[it.id.value]?.current?.title) },
        currentChannelId = channel.id.value,
        onChooseChannel = { id ->
            if (id != target) {
                intentAtMs = SystemClock.elapsedRealtime()
                direction = 0
                target = id
                subtitle = null
                guide = null
            }
        },
        resolver = resolver,
        companionArt = channel.logo?.let { CompanionLibrary.art(CompanionLibrary.CHANNEL, channel.id.value) },
        title = listOfNotNull(channel.number?.toString(), channel.label).joinToString("  "),
        subtitle = subtitle,
        unavailable = unavailable,
        isFavorite = channel.isFavorite,
        onToggleFavorite = { coroutines.launch { graph.setFavorite(channel.id, !channel.isFavorite) } },
        onZap = { delta ->
            if (list.size > 1) {
                intentAtMs = SystemClock.elapsedRealtime()
                direction = delta
                target = list[(index + delta).mod(list.size)].id.value
                subtitle = null
                guide = null
            }
        },
        // "Previous" is the channel the viewer was watching. A channel only counts as watched once a zap has settled on it
        // (passing through one does not), so in the moment between pressing a zap and it settling, the previous channel is
        // the one still playing — not the one before that, which the viewer may have left minutes ago.
        onLastChannel = (playingChannel?.takeIf { it != target } ?: lastChannel)
            ?.takeIf { id -> id != target && list.any { it.id.value == id } }
            ?.let { id ->
                {
                    intentAtMs = SystemClock.elapsedRealtime()
                    target = id
                    subtitle = null
                }
            },
    )
}

private const val ZAP_SETTLE_MS = 350L
private const val DEFAULT_BAR_SECONDS = 3L
private const val MS_PER_SECOND = 1000L

/** The app setting for x7, "Channel bar stays", in seconds. */
const val LIVE_BAR_SECONDS = "live_bar_seconds"

/** The list the last channel was played from, for opening Luz on it (3.9). */
const val LAST_CHANNEL_SCOPE = "last_channel_scope"
private const val GUIDE_AROUND = 60

/** The app setting for 3.9, "Open on the last channel": on unless it holds "false". */
const val OPEN_LAST_CHANNEL = "open_last_channel"
