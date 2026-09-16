package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.LibraryGroupRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.live.EmptyState
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.LuzMenu
import app.iptvplayer.tv.ui.theme.LuzMenuItem
import app.iptvplayer.tv.ui.theme.LuzRow
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

object LibraryTags {
    const val CATEGORY_ALL = "library-category-all"
    const val ADD_SOURCE = "library-add-source"
    const val EMPTY = "library-empty"

    fun category(id: String) = "library-category-$id"

    fun item(id: String) = "library-item-$id"
}

/** One poster in a library grid. */
data class PosterItem(val id: String, val title: String, val caption: String?, val poster: UrlTemplate?, val watched: Float?)

/**
 * Movies or Series (DESIGN_SYSTEM.md §5, FR-VOD-001, FR-SER-001): categories on the left, a poster grid on the right. Items
 * load a page at a time as focus approaches the end, so a provider with tens of thousands of titles opens instantly.
 * Browsing categories previews them after a short pause, like Live TV. OK opens the detail screen.
 */
@Composable
fun LibrarySection(focus: FocusMemory, unit: ImportUnit, onOpen: (PlaylistId, String) -> Unit, onAddSource: () -> Unit) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val activity by graph.activity.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<LibraryGroupRow>>(emptyList()) }
    var status by remember { mutableStateOf<ImportStatus?>(null) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        val source = graph.currentSource()
        playlist = source?.playlistId
        groups = source?.let { graph.libraryGroups(it.playlistId, unit) }.orEmpty()
        status = source?.let { graph.libraryState(it.playlistId, unit)?.status }
        loaded = true
    }
    if (!loaded) return
    val current = playlist
    if (current == null) {
        EmptyState(
            stringResource(R.string.library_no_source),
            stringResource(R.string.playlists_add_source),
            focus,
            LibraryTags.ADD_SOURCE,
            onAddSource,
        )
        return
    }
    val importing = activity[current]?.let { if (unit == ImportUnit.MOVIES) it.moviesRunning else it.seriesRunning } == true
    val resolver = rememberArtworkResolver(current)
    val coroutines = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<PosterItem?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxSize().padding(
                start = Tokens.space8,
                end = Tokens.safeHorizontal,
                top = Tokens.safeVertical,
                bottom = Tokens.safeVertical,
            ),
        ) {
            LazyColumn(
                modifier = Modifier.width(260.dp).fillMaxHeight().focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(Tokens.space2),
            ) {
                item(key = "all") {
                    CategoryItem(
                        stringResource(if (unit == ImportUnit.MOVIES) R.string.library_all_movies else R.string.library_all_series),
                        null,
                        category == null,
                        Modifier.rememberedFocus(focus, LibraryTags.CATEGORY_ALL),
                    ) { category = null }
                }
                items(groups.size, key = { groups[it].id }) { index ->
                    val group = groups[index]
                    CategoryItem(
                        group.title,
                        group.itemCount,
                        category == group.id,
                        Modifier.rememberedFocus(focus, LibraryTags.category(group.id)),
                    ) {
                        category = group.id
                    }
                }
            }
            PosterGrid(
                focus = focus,
                playlist = current,
                unit = unit,
                category = category,
                revision = revision,
                emptyText = stringResource(
                    when {
                        importing || status == null || status == ImportStatus.RUNNING -> R.string.library_importing
                        status == ImportStatus.FAILED -> R.string.library_failed
                        else -> R.string.library_empty
                    },
                ),
                resolver = resolver,
                onOpen = { onOpen(current, it) },
                onMenu = { menuFor = it },
                modifier = Modifier.padding(start = Tokens.space6).weight(1f),
            )
        }

        menuFor?.let { item ->
            val target = if (unit == ImportUnit.MOVIES) CustomisationTarget.MOVIE else CustomisationTarget.SERIES
            LuzMenu(
                title = item.title,
                items = listOf(
                    LuzMenuItem("open", stringResource(R.string.menu_open)) { onOpen(current, item.id) },
                    LuzMenuItem(
                        "hide",
                        stringResource(if (unit == ImportUnit.MOVIES) R.string.menu_hide_movie else R.string.menu_hide_series),
                    ) { coroutines.launch { graph.hide(current, target, item.id) } },
                ),
                onDismiss = {
                    menuFor = null
                    coroutines.launch {
                        repeat(20) {
                            if (focus.requestFocus(LibraryTags.item(item.id))) return@launch
                            delay(50)
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun CategoryItem(title: String, count: Long?, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // As in Live TV: long enough that passing through categories does not rebuild the grid on every step.
    LaunchedEffect(focused) {
        if (focused && !selected) {
            delay(PREVIEW_DELAY)
            onSelect()
        }
    }
    LuzRow(onClick = onSelect, modifier = modifier.onFocusChanged { focused = it.isFocused }, selected = selected) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Tokens.accent else Tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        count?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary) }
    }
}

@Composable
private fun PosterGrid(
    focus: FocusMemory,
    playlist: PlaylistId,
    unit: ImportUnit,
    category: String?,
    revision: Int,
    emptyText: String,
    resolver: ((UrlTemplate) -> String?)?,
    onOpen: (String) -> Unit,
    onMenu: (PosterItem) -> Unit,
    modifier: Modifier,
) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    var items by remember(playlist, category) { mutableStateOf<List<PosterItem>?>(null) }
    var exhausted by remember(playlist, category) { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    suspend fun page(offset: Int): List<PosterItem> = if (unit == ImportUnit.MOVIES) {
        graph.movies(playlist, category, PAGE_SIZE, offset).map {
            PosterItem(it.id, it.title, it.year?.toString(), it.poster, it.progress?.takeIf { p -> !p.completed }?.fraction)
        }
    } else {
        graph.series(playlist, category, PAGE_SIZE, offset).map { PosterItem(it.id, it.title, it.year?.toString(), it.poster, null) }
    }

    LaunchedEffect(playlist, category, revision) {
        val first = page(0)
        items = first
        exhausted = first.size < PAGE_SIZE
    }
    fun loadMore() {
        val current = items ?: return
        if (exhausted || loading) return
        loading = true
        coroutines.launch {
            val next = page(current.size)
            items = current + next
            exhausted = next.size < PAGE_SIZE
            loading = false
        }
    }

    val rows = items ?: return
    Box(modifier = modifier.fillMaxHeight()) {
        if (rows.isEmpty()) {
            Text(
                emptyText,
                style = MaterialTheme.typography.bodyLarge,
                color = Tokens.textSecondary,
                modifier = Modifier.padding(Tokens.space4),
            )
            return@Box
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(Tokens.space4),
            verticalArrangement = Arrangement.spacedBy(Tokens.space6),
        ) {
            items(rows.size, key = { rows[it].id }) { index ->
                val item = rows[index]
                PosterCard(
                    item,
                    resolver,
                    Modifier.rememberedFocus(focus, LibraryTags.item(item.id)).onFocusChanged {
                        if (it.isFocused && index >= rows.size - LOAD_AHEAD) loadMore()
                    },
                    onMenu = { onMenu(item) },
                ) { onOpen(item.id) }
            }
        }
    }
}

@Composable
private fun PosterCard(
    item: PosterItem,
    resolver: ((UrlTemplate) -> String?)?,
    modifier: Modifier,
    onMenu: () -> Unit,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
        Surface(
            onClick = onClick,
            onLongClick = onMenu,
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(Tokens.radiusSmall)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Tokens.bgSurface1, focusedContainerColor = Tokens.bgSurface3),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Tokens.focusRingWidth, Tokens.focusRing))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                ArtworkImage(item.poster, resolver, item.title, Modifier.fillMaxSize())
                WatchedBar(item.watched, Modifier.align(Alignment.BottomStart))
            }
        }
        Text(
            item.title,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textTertiary) }
    }
}

private val PREVIEW_DELAY = 900.milliseconds

private const val PAGE_SIZE = 120
private const val LOAD_AHEAD = 24
