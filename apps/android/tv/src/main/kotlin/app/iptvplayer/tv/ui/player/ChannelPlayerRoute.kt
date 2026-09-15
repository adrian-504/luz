package app.iptvplayer.tv.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
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
import app.iptvplayer.tv.app.LocalAppGraph
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
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    var channels by remember { mutableStateOf<List<ChannelRow>?>(null) }
    var target by rememberSaveable { mutableStateOf(startChannel.value) }
    var request by remember { mutableStateOf<PlaybackRequest?>(null) }
    var unavailable by remember { mutableStateOf(false) }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableIntStateOf(0) }
    var intentAtMs by remember { mutableStateOf<Long?>(null) }
    val history = remember { ChannelHistory<String>() }
    var lastChannel by remember { mutableStateOf<String?>(null) }
    val prepared = remember { mutableMapOf<String, PlaybackRequest>() }

    LaunchedEffect(revision) {
        prepared.clear()
        channels = when (scope) {
            ChannelScope.All -> graph.channels(playlistId, null)
            ChannelScope.Favorites -> graph.favoriteChannels(playlistId)
            is ChannelScope.Group -> graph.channels(playlistId, scope.id)
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
        lastChannel = history.last
        subtitle = graph.nowNext(playlistId, listOf(channel.id), Clock.System.now())[channel.id.value]?.current?.title

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

    PlayerScreen(
        request = request,
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
            }
        },
        onLastChannel = lastChannel?.takeIf { id -> id != target && list.any { it.id.value == id } }?.let { id ->
            {
                intentAtMs = SystemClock.elapsedRealtime()
                target = id
                subtitle = null
            }
        },
    )
}

private const val ZAP_SETTLE_MS = 350L
