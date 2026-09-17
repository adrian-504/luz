package app.iptvplayer.tv.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.playback.ChannelHistory
import app.iptvplayer.domain.playback.PreparationWindow
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.tv.app.LocalAppGraph
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

    LaunchedEffect(revision) {
        prepared.clear()
        channels = when (scope) {
            ChannelScope.All -> graph.channels(playlistId, null)
            ChannelScope.Favorites -> graph.favoriteChannels(playlistId)
            is ChannelScope.Group -> graph.channels(playlistId, scope.id)
            // Zapping stays inside the list the viewer came from, their own groups included.
            is ChannelScope.Mine -> graph.channelsInUserGroup(playlistId, scope.id)
        }
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
        history.played(channel.id.value)
        graph.recordChannelWatch(playlistId, channel.id)
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
        live = LiveInfo(channel.number, channel.name, channel.logo, guide?.current, guide?.next),
        channels = list.map { ChannelChoice(it.id.value, it.number, it.name, it.logo, listGuide[it.id.value]?.current?.title) },
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
        title = listOfNotNull(channel.number?.toString(), channel.name).joinToString("  "),
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
private const val GUIDE_AROUND = 60
