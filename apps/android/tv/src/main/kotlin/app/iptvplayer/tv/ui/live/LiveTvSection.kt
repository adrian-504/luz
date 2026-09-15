package app.iptvplayer.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.GroupRow
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.storage.SourceRecord
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Which channels a list shows; also the zapping order in the player. */
sealed interface ChannelScope {
    data object All : ChannelScope

    data object Favorites : ChannelScope

    data class Group(val id: String) : ChannelScope

    fun key(): String = when (this) {
        All -> "all"
        Favorites -> "favorites"
        is Group -> "g:$id"
    }

    companion object {
        fun of(key: String): ChannelScope = when {
            key == "favorites" -> Favorites
            key.startsWith("g:") -> Group(key.removePrefix("g:"))
            else -> All
        }
    }
}

object LiveTags {
    const val GROUP_ALL = "live-group-all"
    const val GROUP_FAVORITES = "live-group-favorites"
    const val SWITCH_SOURCE = "live-switch-source"

    fun group(id: String) = "live-group-$id"

    fun channel(id: ChannelId) = "live-channel-${id.value}"

    fun emptyAddSource(favorites: Boolean) = if (favorites) "favorites-add-source" else "live-add-source"
}

/**
 * Live TV (DESIGN_SYSTEM.md §5): groups on the left, the selected group's channels with now/next and progress on the right.
 * OK plays; long-press OK toggles the favorite. [favoritesOnly] shows the Favorites section without the group column.
 */
@Composable
fun LiveTvSection(
    focus: FocusMemory,
    favoritesOnly: Boolean,
    onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    onAddSource: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val activity by graph.activity.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<GroupRow>>(emptyList()) }
    var sources by remember { mutableStateOf<List<SourceRecord>>(emptyList()) }
    val coroutines = rememberCoroutineScope()
    var scopeKey by rememberSaveable { mutableStateOf(if (favoritesOnly) ChannelScope.Favorites.key() else ChannelScope.All.key()) }
    val scope = ChannelScope.of(scopeKey)

    LaunchedEffect(revision) {
        val source = graph.currentSource()
        sources = graph.sources()
        playlist = source?.playlistId
        groups = source?.let { graph.groups(it.playlistId) }.orEmpty()
        loaded = true
    }
    if (!loaded) return
    val current = playlist
    if (current == null) {
        EmptyState(
            stringResource(R.string.live_no_source),
            stringResource(R.string.playlists_add_source),
            focus,
            LiveTags.emptyAddSource(favoritesOnly),
            onAddSource,
        )
        return
    }
    val importing = activity[current]?.liveRunning == true

    Row(
        modifier = Modifier.fillMaxSize().padding(
            start = Tokens.space8,
            end = Tokens.safeHorizontal,
            top = Tokens.safeVertical,
            bottom = Tokens.safeVertical,
        ),
    ) {
        if (!favoritesOnly) {
            LazyColumn(
                modifier = Modifier.width(280.dp).fillMaxHeight().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(Tokens.space2),
            ) {
                if (sources.size > 1) {
                    item(key = "source") {
                        val index = sources.indexOfFirst { it.playlistId == current }
                        SourceSwitcher(
                            name = sources.getOrNull(index)?.name.orEmpty(),
                            modifier = Modifier.rememberedFocus(focus, LiveTags.SWITCH_SOURCE),
                        ) {
                            // Few sources are expected on a TV, so OK moves to the next one; the list reloads for it.
                            val next = sources[(index + 1).mod(sources.size)].playlistId
                            scopeKey = if (favoritesOnly) ChannelScope.Favorites.key() else ChannelScope.All.key()
                            coroutines.launch { graph.selectSource(next) }
                        }
                    }
                }
                item(key = "all") {
                    GroupItem(
                        stringResource(R.string.live_all_channels),
                        null,
                        scope == ChannelScope.All,
                        Modifier.rememberedFocus(focus, LiveTags.GROUP_ALL),
                    ) {
                        scopeKey =
                            ChannelScope.All.key()
                    }
                }
                item(key = "favorites") {
                    GroupItem(
                        stringResource(R.string.live_favorites),
                        null,
                        scope == ChannelScope.Favorites,
                        Modifier.rememberedFocus(focus, LiveTags.GROUP_FAVORITES),
                    ) {
                        scopeKey = ChannelScope.Favorites.key()
                    }
                }
                items(groups.size, key = { groups[it].id }) { index ->
                    val group = groups[index]
                    GroupItem(
                        group.title,
                        group.channelCount,
                        scope == ChannelScope.Group(group.id),
                        Modifier.rememberedFocus(focus, LiveTags.group(group.id)),
                    ) {
                        scopeKey = ChannelScope.Group(group.id).key()
                    }
                }
            }
        }
        ChannelList(
            focus,
            current,
            scope,
            revision,
            importing,
            favoritesOnly,
            onPlay,
            Modifier.padding(start = if (favoritesOnly) 0.dp else Tokens.space6).weight(1f),
        )
    }
}

@Composable
private fun SourceSwitcher(name: String, modifier: Modifier, onSwitch: () -> Unit) {
    ListItem(
        selected = false,
        onClick = onSwitch,
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(stringResource(R.string.live_switch_source), style = MaterialTheme.typography.bodySmall) },
        modifier = modifier.padding(bottom = Tokens.space3),
    )
}

@Composable
private fun GroupItem(title: String, count: Long?, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // Browsing groups previews their channels after a short pause, without pressing OK.
    LaunchedEffect(focused) {
        if (focused && !selected) {
            delay(250)
            onSelect()
        }
    }
    ListItem(
        selected = selected,
        onClick = onSelect,
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = count?.let { { Text(it.toString(), style = MaterialTheme.typography.bodySmall) } },
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}

/** Now/next for on-screen channels missing from [known], from the provider's per-channel guide (at most 20 channels). */
private suspend fun visibleFallback(
    graph: AppGraph,
    playlist: PlaylistId,
    rows: List<ChannelRow>,
    listState: LazyListState,
    known: Map<String, NowNextRow>,
    now: Instant,
    visible: List<Int> = listState.layoutInfo.visibleItemsInfo.map { it.index },
): Map<String, NowNextRow> {
    val missing = visible.mapNotNull { rows.getOrNull(it)?.id }.filter { it.value !in known }.take(20)
    return if (missing.isEmpty()) emptyMap() else graph.nowNext(playlist, missing, now)
}

@Composable
private fun ChannelList(
    focus: FocusMemory,
    playlist: PlaylistId,
    scope: ChannelScope,
    revision: Int,
    importing: Boolean,
    favoritesOnly: Boolean,
    onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    modifier: Modifier,
) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    var channels by remember(scope) { mutableStateOf<List<ChannelRow>?>(null) }
    var guide by remember(scope) { mutableStateOf<Map<String, NowNextRow>>(emptyMap()) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    val listState = rememberLazyListState()

    LaunchedEffect(playlist, scope, revision) {
        val rows = when (scope) {
            ChannelScope.All -> graph.channels(playlist, null)
            ChannelScope.Favorites -> graph.favoriteChannels(playlist)
            is ChannelScope.Group -> graph.channels(playlist, scope.id)
        }
        channels = rows
        while (true) {
            now = Clock.System.now()
            val stored = rows.chunked(500).flatMap { chunk -> graph.nowNext(playlist, chunk.map { it.id }, now).entries }
                .associate { it.key to it.value }
            guide = stored + visibleFallback(graph, playlist, rows, listState, stored, now)
            delay(1.minutes)
        }
    }
    // Channels on screen without stored guide data: ask the provider's per-channel guide once scrolling settles.
    LaunchedEffect(playlist, scope, revision, channels) {
        val rows = channels ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collectLatest { visible ->
                delay(400)
                guide = guide + visibleFallback(graph, playlist, rows, listState, guide, Clock.System.now(), visible)
            }
    }

    val rows = channels
    Box(modifier = modifier.fillMaxHeight()) {
        when {
            rows == null -> Unit
            rows.isEmpty() -> Text(
                stringResource(
                    when {
                        importing -> R.string.live_importing
                        scope == ChannelScope.Favorites -> R.string.live_no_favorites
                        else -> R.string.live_no_channels
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.textSecondary,
                modifier = Modifier.padding(Tokens.space4),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(Tokens.space2),
            ) {
                items(rows.size, key = { rows[it].id.value }) { index ->
                    val channel = rows[index]
                    ChannelItem(
                        channel = channel,
                        guide = guide[channel.id.value],
                        now = now,
                        modifier = Modifier.rememberedFocus(focus, LiveTags.channel(channel.id)),
                        onPlay = { onPlay(playlist, scope, channel.id) },
                        onToggleFavorite = { coroutines.launch { graph.setFavorite(channel.id, !channel.isFavorite) } },
                    )
                }
            }
        }
        if (favoritesOnly && rows != null && rows.isNotEmpty()) Unit
    }
}

@Composable
private fun ChannelItem(
    channel: ChannelRow,
    guide: NowNextRow?,
    now: kotlin.time.Instant,
    modifier: Modifier,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val current = guide?.current
    ListItem(
        selected = false,
        onClick = onPlay,
        onLongClick = onToggleFavorite,
        leadingContent = {
            Text(
                channel.number?.toString() ?: "",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.width(44.dp),
            )
        },
        headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    current?.let { stringResource(R.string.live_now, it.title) } ?: stringResource(R.string.live_no_guide),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (current != null) {
                    val progress = ((now - current.start) / (current.end - current.start)).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Tokens.lineSubtle)) {
                        Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Tokens.accent))
                    }
                }
                guide?.next?.let {
                    Text(
                        stringResource(R.string.live_next, it.title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Tokens.textTertiary,
                    )
                }
            }
        },
        trailingContent = if (channel.isFavorite) {
            { Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.section_favorites)) }
        } else {
            null
        },
        modifier = modifier,
    )
}

@Composable
fun EmptyState(message: String, action: String, focus: FocusMemory, actionKey: String, onAction: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(
            horizontal = Tokens.safeHorizontal + Tokens.space8,
            vertical =
            Tokens.safeVertical + Tokens.space8,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.space4),
    ) {
        Text(message, style = MaterialTheme.typography.titleLarge, color = Tokens.textSecondary)
        ActionButton(action, onAction, Modifier.rememberedFocus(focus, actionKey))
    }
}
