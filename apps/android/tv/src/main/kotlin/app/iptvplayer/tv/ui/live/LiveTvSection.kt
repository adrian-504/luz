package app.iptvplayer.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.StableLists
import app.iptvplayer.domain.live.GroupNames
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.GroupRow
import app.iptvplayer.storage.GuideProgramme
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.storage.SourceRecord
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.app.CopyChoice
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.UpdatingNote
import app.iptvplayer.tv.ui.library.ArtworkImage
import app.iptvplayer.tv.ui.library.ListMaker
import app.iptvplayer.tv.ui.library.rememberArtworkResolver
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.shortTime
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.LuzBadge
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzEmptyState
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.LuzMenu
import app.iptvplayer.tv.ui.theme.LuzMenuItem
import app.iptvplayer.tv.ui.theme.LuzPrompt
import app.iptvplayer.tv.ui.theme.LuzRow
import app.iptvplayer.tv.ui.theme.LuzSkeletonRows
import app.iptvplayer.tv.ui.theme.NoteText
import app.iptvplayer.tv.ui.theme.ProgressBar
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Which channels a list shows; also the zapping order in the player. */
sealed interface ChannelScope {
    data object All : ChannelScope

    data object Favorites : ChannelScope

    data class Group(val id: String) : ChannelScope

    /** One of the viewer's own groups (FR-FAV-002), which holds channels they chose rather than ones a provider grouped. */
    data class Mine(val id: String) : ChannelScope

    /** The channels watched last (the strip's Recent tab, ADR-0053). */
    data object Recent : ChannelScope

    /** News channels in Arabic, French and English (the strip's News tab, s0). */
    data object News : ChannelScope

    fun key(): String = when (this) {
        All -> "all"
        Favorites -> "favorites"
        is Group -> "g:$id"
        is Mine -> "m:$id"
        Recent -> "recent"
        News -> "news"
    }

    companion object {
        fun of(key: String): ChannelScope = when {
            key == "favorites" -> Favorites
            key == "recent" -> Recent
            key == "news" -> News
            key.startsWith("g:") -> Group(key.removePrefix("g:"))
            key.startsWith("m:") -> Mine(key.removePrefix("m:"))
            else -> All
        }
    }
}

object LiveTags {
    const val GROUP_ALL = "live-group-all"
    const val NEW_LIST = "live-new-list"
    const val PREVIEW_NOT_PLAYING = "live-preview-not-playing"
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
    favoritesOnly: Boolean = false,
    onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    onAddSource: () -> Unit,
    /** Opens the guide on a channel's row (item 34). */
    onOpenGuide: (ChannelId) -> Unit = {},
) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val activity by graph.activity.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<GroupRow>>(emptyList()) }
    var myGroups by remember { mutableStateOf<List<GroupRow>>(emptyList()) }
    var sources by remember { mutableStateOf<List<SourceRecord>>(emptyList()) }
    val coroutines = rememberCoroutineScope()
    var scopeKey by rememberSaveable { mutableStateOf(if (favoritesOnly) ChannelScope.Favorites.key() else ChannelScope.All.key()) }
    val scope = ChannelScope.of(scopeKey)
    // Set when a category is chosen: the channels take focus as soon as they are on screen (ADR-0032).
    var handOverFocus by remember { mutableStateOf(false) }
    // A channel's menu and its information panel belong to the screen, so they dim all of it, rail included.
    var menuFor by remember { mutableStateOf<Pair<ChannelRow, NowNextRow?>?>(null) }
    var infoFor by remember { mutableStateOf<Pair<ChannelRow, NowNextRow?>?>(null) }
    var groupMenuFor by remember { mutableStateOf<GroupRow?>(null) }
    var renaming by remember { mutableStateOf<GroupRow?>(null) }
    var myGroupMenuFor by remember { mutableStateOf<GroupRow?>(null) }
    var renamingMine by remember { mutableStateOf<GroupRow?>(null) }
    // Design 2.9: making a list here (null id) or adding more to one; the outer null means the maker is closed.
    var making by remember { mutableStateOf<Making?>(null) }
    var addingToGroup by remember { mutableStateOf<ChannelRow?>(null) }
    // x9: the copies of a channel, to choose which one plays.
    var copiesFor by remember { mutableStateOf<Pair<ChannelRow, List<CopyChoice>>?>(null) }
    var copyCount by remember { mutableStateOf(0) }
    var newGroupFor by remember { mutableStateOf<ChannelRow?>(null) }

    LaunchedEffect(revision) {
        val source = graph.currentSource()
        sources = graph.sources()
        playlist = source?.playlistId
        groups = source?.let { graph.groups(it.playlistId) }.orEmpty()
        // Groups of channels, and new empty ones; a group the viewer filled with films belongs to Movies and Series.
        myGroups = source?.let { graph.userGroups(it.playlistId) }.orEmpty()
            .filter { it.channelCount > 0 || it.movieCount + it.seriesCount == 0L }
        loaded = true
    }
    if (!loaded) return LuzSkeletonRows(Modifier.padding(start = Tokens.space6, top = Tokens.space16))
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
    // Luz opens on the category used last with this source (ADR-0051): opening on a category reads a few dozen rows,
    // "All channels" thousands. Read once per source before anything is written back; a category that is gone falls
    // back to All.
    var scopeRestored by rememberSaveable(current.value) { mutableStateOf(favoritesOnly) }
    LaunchedEffect(current, scopeRestored) {
        val key = graph.liveScopeKey(current)
        if (!scopeRestored) {
            val saved = graph.preference(key)?.let(ChannelScope::of)
            val exists = when (saved) {
                is ChannelScope.Group -> groups.any { it.id == saved.id }
                is ChannelScope.Mine -> myGroups.any { it.id == saved.id }
                ChannelScope.Favorites -> true
                else -> false
            }
            if (saved != null && exists && scope == ChannelScope.All) scopeKey = saved.key()
            scopeRestored = true
        }
    }
    LaunchedEffect(current, scopeKey, scopeRestored) {
        if (scopeRestored && !favoritesOnly) graph.setPreference(graph.liveScopeKey(current), scopeKey)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(
                start = Tokens.space6,
                end = Tokens.safeHorizontal,
                top = Tokens.space8,
            ),
            verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4), verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(if (favoritesOnly) R.string.favorites_title else R.string.live_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = Tokens.textPrimary,
                    modifier = Modifier.padding(start = Tokens.space4),
                )
                // Design 1.1: the only sign of a refresh, beside the title.
                UpdatingNote(
                    when {
                        importing -> stringResource(R.string.live_updating_channels)
                        activity[current]?.guideRunning == true -> stringResource(R.string.live_updating_guide)
                        else -> null
                    },
                    Modifier.padding(bottom = Tokens.space2),
                )
            }
            Row(modifier = Modifier.fillMaxSize()) {
                if (!favoritesOnly) {
                    LazyColumn(
                        modifier = Modifier.width(CATEGORY_WIDTH).fillMaxHeight().focusRestorer(),
                        verticalArrangement = Arrangement.spacedBy(Tokens.space1),
                        contentPadding = PaddingValues(bottom = Tokens.space8),
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
                                scopeKey = ChannelScope.All.key()
                                handOverFocus = true
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
                                handOverFocus = true
                            }
                        }
                        items(myGroups.size, key = { myGroups[it].id }) { index ->
                            val group = myGroups[index]
                            GroupItem(
                                group.title,
                                group.channelCount,
                                scope == ChannelScope.Mine(group.id),
                                Modifier.rememberedFocus(focus, LiveTags.group(group.id)),
                                onMenu = { myGroupMenuFor = group },
                            ) {
                                scopeKey = ChannelScope.Mine(group.id).key()
                                handOverFocus = true
                            }
                        }
                        item(key = "new-list") {
                            GroupItem(
                                stringResource(R.string.list_new_menu),
                                null,
                                false,
                                Modifier.rememberedFocus(focus, LiveTags.NEW_LIST),
                            ) { making = Making(null) }
                        }
                        items(groups.size, key = { groups[it].id }) { index ->
                            val group = groups[index]
                            GroupItem(
                                // The provider's capitals read as sentence case; the viewer's own lists keep their names (D2).
                                GroupNames.display(group.title),
                                group.channelCount,
                                scope == ChannelScope.Group(group.id),
                                Modifier.rememberedFocus(focus, LiveTags.group(group.id)),
                                pinned = group.pinned,
                                onMenu = { groupMenuFor = group },
                            ) {
                                scopeKey = ChannelScope.Group(group.id).key()
                                handOverFocus = true
                            }
                        }
                    }
                }
                // Not before the last category is known: reading "All channels" first and then the category costs twice.
                if (scopeRestored) {
                    ChannelList(
                        focus,
                        current,
                        scope,
                        revision,
                        importing,
                        favoritesOnly,
                        onPlay,
                        handOverFocus,
                        { handOverFocus = false },
                        { channel, guide -> menuFor = channel to guide },
                        Modifier.padding(start = if (favoritesOnly) 0.dp else Tokens.space8).weight(1f),
                        menusClosed = menuFor == null && infoFor == null && copiesFor == null && making == null &&
                            addingToGroup == null && newGroupFor == null,
                    )
                }
            }
        }

        LaunchedEffect(menuFor) { copyCount = menuFor?.let { graph.channelCopies(current, it.first.id).size } ?: 0 }
        copiesFor?.let { (channel, copies) ->
            LuzMenu(
                title = stringResource(R.string.menu_choose_copy),
                subtitle = pluralStringResource(R.plurals.copies_intro, copies.size, copies.size),
                items = copies.map { copy ->
                    // Nothing is playing in this list, so only how each copy did last time (the player's menu says "Playing").
                    val note = if (copy.startedWell) stringResource(R.string.copies_good) else null
                    val name = copy.quality ?: channel.label
                    LuzMenuItem(copy.id.value, listOfNotNull(name, note).joinToString(" · "), checked = copy.chosen) {
                        // Plays that copy and keeps it first until another is chosen (x9).
                        coroutines.launch {
                            graph.chooseCopy(current, copy.id)
                            onPlay(current, scope, copy.id)
                        }
                    }
                },
                onDismiss = {
                    copiesFor = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.channel(channel.id)) }
                },
            )
        }
        menuFor?.let { (channel, guide) ->
            val back = {
                menuFor = null
                coroutines.launch { returnFocusTo(focus, LiveTags.channel(channel.id)) }
                Unit
            }
            // Which channel the menu is for, over the dimmed screen (Design 3.4).
            val listName = when (scope) {
                is ChannelScope.Group -> groups.firstOrNull { it.id == scope.id }?.title?.let(GroupNames::display)
                is ChannelScope.Mine -> myGroups.firstOrNull { it.id == scope.id }?.title
                else -> null
            }
            // "Channel 2 · Sports" in a named list; "Channel 2" alone under All channels or Favourites; nothing without a number.
            val header = channel.number?.let { number ->
                if (listName != null) {
                    stringResource(R.string.menu_header_line, number, listName)
                } else {
                    stringResource(R.string.menu_header_number, number)
                }
            }
            // In Design 3.4's order: Watch, favourite, lists, copies, hide, information, guide.
            LuzMenu(
                title = channel.label,
                subtitle = header,
                items = listOf(
                    LuzMenuItem("play", stringResource(R.string.menu_watch)) { onPlay(current, scope, channel.id) },
                    LuzMenuItem(
                        "favorite",
                        stringResource(if (channel.isFavorite) R.string.menu_remove_favorite else R.string.menu_add_favorite),
                    ) { coroutines.launch { graph.setFavorite(channel.id, !channel.isFavorite) } },
                    LuzMenuItem("add-to-group", stringResource(R.string.menu_add_to_list)) { addingToGroup = channel },
                ) + listOfNotNull(
                    // Only when the provider sends this channel more than once; the copy in use at the right.
                    LuzMenuItem("choose-copy", stringResource(R.string.menu_choose_copy), value = channel.quality) {
                        coroutines.launch { copiesFor = channel to graph.copyChoices(current, channel.id) }
                    }.takeIf { copyCount > 1 },
                ) + listOf(
                    LuzMenuItem("hide", stringResource(R.string.menu_hide_channel)) {
                        coroutines.launch { graph.hide(current, CustomisationTarget.CHANNEL, channel.id.value) }
                    },
                    LuzMenuItem("info", stringResource(R.string.menu_information)) { infoFor = channel to guide },
                    LuzMenuItem("guide", stringResource(R.string.menu_open_guide)) { onOpenGuide(channel.id) },
                ),
                onDismiss = back,
            )
        }
        addingToGroup?.let { channel ->
            val holding = remember(channel.id, myGroups) { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(channel.id, revision) { holding.value = graph.groupsHolding(current, ContentType.CHANNEL, channel.id.value) }
            // His lists with ticks (Design 2.10): a channel can be in several; OK puts it in or takes it out.
            LuzMenu(
                title = stringResource(R.string.list_add_title, channel.label),
                items = myGroups.map { group ->
                    val inIt = group.id in holding.value
                    LuzMenuItem(group.id, group.title, checked = inIt) {
                        coroutines.launch {
                            if (inIt) {
                                graph.removeFromGroup(current, group.id, ContentType.CHANNEL, channel.id.value)
                            } else {
                                graph.addToGroup(current, group.id, ContentType.CHANNEL, channel.id.value)
                            }
                        }
                    }
                } + LuzMenuItem("new-group", stringResource(R.string.list_new_menu)) { newGroupFor = channel },
                onDismiss = {
                    addingToGroup = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.channel(channel.id)) }
                },
            )
        }
        newGroupFor?.let { channel ->
            LuzPrompt(
                title = stringResource(R.string.list_new_menu),
                initial = "",
                onConfirm = { name ->
                    graph.createGroupWith(current, name, ContentType.CHANNEL, channel.id.value)
                },
                onClear = null,
                onDismiss = {
                    newGroupFor = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.channel(channel.id)) }
                },
            )
        }
        making?.let { make ->
            ListMaker(current, make.listId) { made ->
                making = null
                if (made != null) scopeKey = ChannelScope.Mine(made).key()
                coroutines.launch { returnFocusTo(focus, if (made != null) LiveTags.group(made) else LiveTags.NEW_LIST) }
            }
        }
        myGroupMenuFor?.let { group ->
            LuzMenu(
                title = group.title,
                items = listOf(
                    LuzMenuItem("add-more", stringResource(R.string.list_add_more)) { making = Making(group.id) },
                    LuzMenuItem("rename", stringResource(R.string.menu_rename_list)) { renamingMine = group },
                    LuzMenuItem("delete", stringResource(R.string.menu_delete_list)) {
                        coroutines.launch {
                            if (scope == ChannelScope.Mine(group.id)) scopeKey = ChannelScope.All.key()
                            graph.deleteGroup(current, group.id)
                        }
                    },
                ),
                onDismiss = {
                    myGroupMenuFor = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.group(group.id)) }
                },
            )
        }
        renamingMine?.let { group ->
            LuzPrompt(
                title = stringResource(R.string.menu_rename_list),
                initial = group.title,
                onConfirm = { coroutines.launch { graph.renameGroup(current, group.id, it) } },
                onClear = null,
                onDismiss = {
                    renamingMine = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.group(group.id)) }
                },
            )
        }
        groupMenuFor?.let { group ->
            LuzMenu(
                title = group.title,
                items = listOf(
                    LuzMenuItem("pin", stringResource(if (group.pinned) R.string.menu_unpin_category else R.string.menu_pin_category)) {
                        coroutines.launch { graph.pin(current, CustomisationTarget.CHANNEL_GROUP, group.id, !group.pinned) }
                    },
                    LuzMenuItem("rename", stringResource(R.string.menu_rename_category)) { renaming = group },
                    LuzMenuItem("hide-category", stringResource(R.string.menu_hide_category)) {
                        coroutines.launch { graph.hide(current, CustomisationTarget.CHANNEL_GROUP, group.id) }
                    },
                ),
                onDismiss = {
                    groupMenuFor = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.group(group.id)) }
                },
            )
        }
        renaming?.let { group ->
            LuzPrompt(
                title = stringResource(R.string.menu_rename_category),
                initial = group.title,
                onConfirm = { coroutines.launch { graph.setLabel(current, CustomisationTarget.CHANNEL_GROUP, group.id, it) } },
                onClear = { coroutines.launch { graph.setLabel(current, CustomisationTarget.CHANNEL_GROUP, group.id, "") } },
                onDismiss = {
                    renaming = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.group(group.id)) }
                },
            )
        }
        infoFor?.let { (channel, guide) ->
            LuzMenu(
                title = channel.label,
                items = listOfNotNull(
                    channel.number?.let { LuzMenuItem("number", stringResource(R.string.info_channel_number, it)) {} },
                    guide?.current?.let { LuzMenuItem("now", stringResource(R.string.live_now, it.title)) {} },
                    guide?.next?.let { LuzMenuItem("next", stringResource(R.string.info_next, it.title)) {} },
                    LuzMenuItem("close", stringResource(R.string.info_close)) {},
                ),
                onDismiss = {
                    infoFor = null
                    coroutines.launch { returnFocusTo(focus, LiveTags.channel(channel.id)) }
                },
            )
        }
    }
}

@Composable
private fun SourceSwitcher(name: String, modifier: Modifier, onSwitch: () -> Unit) {
    LuzRow(onClick = onSwitch, modifier = modifier.padding(bottom = Tokens.space3)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = Tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(stringResource(R.string.live_switch_source), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
        }
    }
}

/** The list maker on Live TV: a new list, or more for [listId]. */
private data class Making(val listId: String?)

@Composable
private fun GroupItem(
    title: String,
    count: Long?,
    selected: Boolean,
    modifier: Modifier,
    pinned: Boolean = false,
    onMenu: (() -> Unit)? = null,
    onSelect: () -> Unit,
) {
    // Moving through categories changes nothing: OK chooses one (ADR-0032). Loading a category's channels on every step
    // meant redrawing the whole screen while the viewer was still looking for the category they wanted.
    LuzRow(onClick = onSelect, modifier = modifier, selected = selected, onLongClick = onMenu) {
        // The chosen category is white on faint glass; the rest are grey. No colour: a list of words stays calm.
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Tokens.textPrimary else Tokens.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (pinned) Icon(LuzIcons.Pin, contentDescription = null, tint = Tokens.textTertiary, modifier = Modifier.size(PIN_SIZE))
        count?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary) }
    }
}

private val PIN_SIZE = 14.dp

/** Now/next for on-screen channels missing from [known], from the provider's per-channel guide (at most 20 channels). */
private suspend fun visibleFallback(
    graph: AppGraph,
    playlist: PlaylistId,
    rows: List<ChannelRow>,
    listState: LazyListState,
    known: (String) -> Boolean,
    now: Instant,
    visible: List<Int> = listState.layoutInfo.visibleItemsInfo.map { it.index },
): Map<String, NowNextRow> {
    // A predicate, not a set: combining the two guide maps into one would copy thousands of keys where the UI runs.
    val missing = visible.mapNotNull { rows.getOrNull(it)?.id }.filterNot { known(it.value) }.take(20)
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
    takeFocus: Boolean,
    onFocusTaken: () -> Unit,
    onMenu: (ChannelRow, NowNextRow?) -> Unit,
    modifier: Modifier,
    /** False while a menu or prompt covers Live TV: the preview stops (ADR-0053). */
    menusClosed: Boolean = true,
) {
    val graph = LocalAppGraph.current
    // The preview plays only while the remote is in the channel list: on the rail or the categories it stops.
    var listHasFocus by remember { mutableStateOf(false) }
    val coroutines = rememberCoroutineScope()
    val resolver = rememberArtworkResolver(playlist)
    // A list shown before comes back at once from memory; placeholder rows only for one never shown (ADR-0051).
    var channels by remember(scope) { mutableStateOf(graph.screens.channels(playlist, scope.key())) }
    // The channel under the remote, for the panel above the list. Only the panel reads it, so moving down the list
    // repaints the panel and two rows — never the list (PERFORMANCE.md §6.2).
    val underRemote = remember(scope) { mutableStateOf<ChannelRow?>(null) }
    // Two maps, never merged: the stored guide for the whole list (large) and the handful fetched for what is on screen.
    // Merging them produced a copy of thousands of entries on the UI thread every time either changed (PERFORMANCE.md §6.2).
    var storedGuide by remember(scope) { mutableStateOf<Map<String, NowNextRow>>(emptyMap()) }
    var onScreenGuide by remember(scope) { mutableStateOf<Map<String, NowNextRow>>(emptyMap()) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    val listState = rememberLazyListState()

    LaunchedEffect(playlist, scope, revision) {
        val rows = when (scope) {
            ChannelScope.All -> graph.channels(playlist, null)
            ChannelScope.Favorites -> graph.favoriteChannels(playlist)
            is ChannelScope.Group -> graph.channels(playlist, scope.id)
            is ChannelScope.Mine -> graph.channelsInUserGroup(playlist, scope.id)
            ChannelScope.Recent -> graph.recentChannels(playlist, RECENT_CHANNELS)
            ChannelScope.News -> graph.newsChannels(playlist).values.flatten()
        }
        val before = channels
        // Replaced in place, and only when something changed: the rows keep their keys, so the remote stays on its channel.
        if (rows != before) {
            channels = rows
            graph.screens.keepChannels(playlist, scope.key(), rows)
            if (before != null) keepRemoteNear(focus, before, rows)
        }
        while (true) {
            now = Clock.System.now()
            val stored = graph.storedNowNext(playlist, rows, now)
            storedGuide = stored
            onScreenGuide = visibleFallback(graph, playlist, rows, listState, { it in stored }, now)
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
                val known = { id: String -> id in storedGuide || id in onScreenGuide }
                onScreenGuide = onScreenGuide +
                    visibleFallback(graph, playlist, rows, listState, known, Clock.System.now(), visible)
            }
    }

    val rows = channels
    LaunchedEffect(rows, takeFocus) {
        val first = rows?.firstOrNull() ?: return@LaunchedEffect
        if (!takeFocus) return@LaunchedEffect
        // The rows attach a moment after the list is set, so ask a few times before giving up.
        repeat(FOCUS_ATTEMPTS) {
            if (focus.requestFocus(LiveTags.channel(first.id))) {
                onFocusTaken()
                return@LaunchedEffect
            }
            delay(FOCUS_RETRY_MS)
        }
        onFocusTaken()
    }
    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(Tokens.space4)) {
        when {
            rows == null -> LuzSkeletonRows(Modifier.padding(top = ON_NOW_HEIGHT + Tokens.space4))
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
            else -> {
                // Before the remote reaches a channel, the panel speaks for the first one, so it is never an empty space.
                OnNowPanel(
                    underRemote,
                    rows.firstOrNull(),
                    { id -> onScreenGuide[id] ?: storedGuide[id] },
                    now,
                    resolver,
                    playlist,
                    previewing = menusClosed && listHasFocus,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().focusRestorer().onFocusChanged { listHasFocus = it.hasFocus },
                    verticalArrangement = Arrangement.spacedBy(Tokens.space1),
                    contentPadding = PaddingValues(bottom = Tokens.space8),
                ) {
                    items(rows.size, key = { rows[it].id.value }) { index ->
                        val channel = rows[index]
                        ChannelItem(
                            channel = channel,
                            guide = onScreenGuide[channel.id.value] ?: storedGuide[channel.id.value],
                            now = now,
                            resolver = resolver,
                            modifier = Modifier.rememberedFocus(focus, LiveTags.channel(channel.id)),
                            onFocused = { underRemote.value = channel },
                            onPlay = { onPlay(playlist, scope, channel.id) },
                            onMenu = { onMenu(channel, onScreenGuide[channel.id.value] ?: storedGuide[channel.id.value]) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * If the channel under the remote vanished in a refresh, the remote takes the channel now in its place (Design 1.2). The
 * only time data moves the remote, and only to fill a place that is gone; anywhere else on screen, nothing happens.
 */
private suspend fun keepRemoteNear(focus: FocusMemory, before: List<ChannelRow>, after: List<ChannelRow>) {
    val lost = before.firstOrNull { LiveTags.channel(it.id) == focus.lastFocusedKey } ?: return
    if (after.any { it.id == lost.id }) return
    val target = StableLists.keepNear(before.map { it.id }, after.map { it.id }, lost.id) ?: return
    returnFocusTo(focus, LiveTags.channel(target))
}

/**
 * What is on the channel under the remote, above the list: the channel's logo on a plate, the programme large with its
 * time and how far through it is, what comes next and the first line of what it is about. With no guide for the channel
 * its name stands large instead, so the panel is never an empty space. It is the list's context: it changes as the
 * remote moves and the list itself stays still.
 */
@Composable
private fun OnNowPanel(
    underRemote: State<ChannelRow?>,
    fallback: ChannelRow?,
    guideFor: (String) -> NowNextRow?,
    now: Instant,
    resolver: ((UrlTemplate) -> String?)?,
    playlist: PlaylistId,
    previewing: Boolean,
) {
    val channel = underRemote.value ?: fallback
    var previewFailed by remember(channel?.id) { mutableStateOf(false) }
    val guide = channel?.let { guideFor(it.id.value) }
    val current = guide?.current
    Row(
        modifier = Modifier.fillMaxWidth().height(ON_NOW_HEIGHT).padding(start = Tokens.space4),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space5),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Tokens.space1, Alignment.Bottom)) {
            Text(
                if (current !=
                    null
                ) {
                    channel.label.let { stringResource(R.string.live_on_now) + " · " + it }
                } else {
                    stringResource(R.string.live_on_now)
                },
                style = MaterialTheme.typography.labelMedium,
                color = Tokens.textTertiary,
                maxLines = 1,
            )
            Text(
                current?.title ?: channel?.label.orEmpty(),
                style = MaterialTheme.typography.headlineMedium,
                color = Tokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (current != null) {
                // "21:00 – 22:55 · 40 min left" with the bar (Design 3.1); the guide stores no category for "LaLiga ·".
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), verticalAlignment = Alignment.CenterVertically) {
                    val left = (current.end - now).inWholeMinutes.coerceAtLeast(1)
                    Text(
                        stringResource(R.string.live_time_range, shortTime(current.start), shortTime(current.end)) + " · " +
                            stringResource(R.string.home_minutes_left, left),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.textSecondary,
                        maxLines = 1,
                    )
                    Box(Modifier.width(ON_NOW_BAR)) { ProgressBar(progressOf(current, now), Modifier.padding(0.dp)) }
                }
            } else if (channel != null) {
                // No guide: one quiet line under the name.
                Text(stringResource(R.string.live_no_listings), style = NoteText, color = Tokens.textSecondary, maxLines = 1)
            }
            guide?.next?.let {
                Text(
                    stringResource(R.string.live_next_at, shortTime(it.start), it.title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The preview would not start: the plate stays and the band says so (Design 3.1).
            if (previewFailed) {
                Text(
                    stringResource(R.string.live_not_playing),
                    style = NoteText,
                    color = Tokens.textSecondary,
                    modifier = Modifier.testTag(LiveTags.PREVIEW_NOT_PLAYING),
                )
            }
        }
        // D2: the channel under the remote, playing muted in a box at the right once the remote rests (ADR-0053).
        LivePreview(
            playlist,
            channel,
            previewing,
            resolver,
            Modifier.width(PREVIEW_WIDTH).height(ON_NOW_HEIGHT),
            onFailed = { previewFailed = it },
        )
    }
}

/**
 * One channel: its logo on a small plate, its number, its name, and what is on — with a thin line for how far through
 * the programme is. A favourite carries a small heart. The row itself has no box until the remote is on it.
 */
@Composable
private fun ChannelItem(
    channel: ChannelRow,
    guide: NowNextRow?,
    now: Instant,
    resolver: ((UrlTemplate) -> String?)?,
    modifier: Modifier,
    onFocused: () -> Unit,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
) {
    val current = guide?.current
    LuzRow(onClick = onPlay, modifier = modifier, onLongClick = onMenu, onFocusChange = { if (it) onFocused() }) {
        ArtworkImage(
            channel.logo,
            resolver,
            null,
            Modifier.width(LOGO_WIDTH).height(LOGO_HEIGHT).clip(RoundedCornerShape(Tokens.radiusSmall)),
            fit = true,
            inset = Tokens.space1,
            name = channel.label,
        )
        Text(
            channel.number?.toString() ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Tokens.textTertiary,
            maxLines = 1,
            modifier = Modifier.width(NUMBER_WIDTH),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    channel.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = Tokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // 4K, FHD or HD, the copy that will play (3.11); never SD, which says nothing worth a badge.
                channel.quality?.takeIf { it != "SD" }?.let { LuzBadge(it) }
                if (channel.isFavorite) {
                    // The amber star, the favourite's mark on the TV and the phone alike (Design 2b, 3).
                    Icon(
                        LuzIcons.Star,
                        contentDescription = stringResource(R.string.section_favorites),
                        tint = Tokens.accent,
                        modifier = Modifier.size(FAVORITE_MARK),
                    )
                }
            }
            // A channel with nothing listed has just its name: a line saying so on every row says nothing new.
            current?.let { programme ->
                Text(
                    programme.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Tokens.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // How far through, directly under what is on (D2): thin, amber, on a faint white track.
                Box(Modifier.padding(top = Tokens.space1).width(ROW_BAR).height(ROW_BAR_HEIGHT).background(ROW_BAR_TRACK)) {
                    Box(Modifier.fillMaxWidth(progressOf(programme, now)).height(ROW_BAR_HEIGHT).background(Tokens.accent))
                }
            }
        }
    }
}

private fun progressOf(programme: GuideProgramme, now: Instant): Float =
    ((now - programme.start) / (programme.end - programme.start)).toFloat().coerceIn(0f, 1f)

/** A section with nothing to show yet: what will be here, and the one thing to do about it. */
@Composable
fun EmptyState(message: String, action: String, focus: FocusMemory, actionKey: String, onAction: () -> Unit) {
    LuzEmptyState(title = message, message = null) {
        LuzButton(action, onAction, Modifier.rememberedFocus(focus, actionKey), kind = ButtonKind.PRIMARY, icon = LuzIcons.Add)
    }
}

private val CATEGORY_WIDTH = 220.dp

// D2: the preview box is 288 × 162 (16:9); the words beside it share its height.
private val ON_NOW_HEIGHT = 162.dp
private val PREVIEW_WIDTH = 288.dp
private val ON_NOW_BAR = 120.dp
private val ROW_BAR = 120.dp
private val ROW_BAR_HEIGHT = 2.dp
private val ROW_BAR_TRACK = Color.White.copy(alpha = 0.25f)
private val LOGO_WIDTH = 56.dp
private val LOGO_HEIGHT = 34.dp

// Room for a four-digit number at 15 sp: large providers send twelve thousand channels.
private val NUMBER_WIDTH = 40.dp
private val FAVORITE_MARK = 12.dp

/** How long the channels get to appear before the handover gives up (ADR-0032). */
private const val FOCUS_ATTEMPTS = 20
private const val FOCUS_RETRY_MS = 50L

/** Puts focus back on the row a menu was opened from; the row re-attaches a moment after the overlay closes. */
private suspend fun returnFocusTo(focus: FocusMemory, key: String) {
    repeat(FOCUS_ATTEMPTS) {
        if (focus.requestFocus(key)) return
        delay(FOCUS_RETRY_MS)
    }
}

/** How many channels Recent holds (the strip, ADR-0053). */
const val RECENT_CHANNELS = 20
