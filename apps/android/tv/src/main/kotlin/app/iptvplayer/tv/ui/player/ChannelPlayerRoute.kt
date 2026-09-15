package app.iptvplayer.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
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
 * stream instead of one per press.
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

    LaunchedEffect(revision) {
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
        val resolved = graph.playbackRequest(playlistId, channel.id)
        unavailable = resolved == null
        request = resolved
        subtitle = graph.nowNext(playlistId, listOf(channel.id), Clock.System.now())[channel.id.value]?.current?.title
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
                target = list[(index + delta).mod(list.size)].id.value
                subtitle = null
            }
        },
    )
}

private const val ZAP_SETTLE_MS = 350L
