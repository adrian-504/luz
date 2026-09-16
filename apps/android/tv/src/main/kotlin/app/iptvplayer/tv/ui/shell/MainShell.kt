package app.iptvplayer.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.PlaceholderPage
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.guide.GuideSection
import app.iptvplayer.tv.ui.guide.GuideTags
import app.iptvplayer.tv.ui.library.HomeSection
import app.iptvplayer.tv.ui.library.LibrarySection
import app.iptvplayer.tv.ui.library.LibraryTags
import app.iptvplayer.tv.ui.library.SearchSection
import app.iptvplayer.tv.ui.library.SearchTags
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.LiveTags
import app.iptvplayer.tv.ui.live.LiveTvSection
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.sources.SourcesList
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Top-level sections (DESIGN_SYSTEM.md §2) with the roadmap phase that fills them, when planned. */
enum class Section(@param:StringRes val title: Int, val icon: ImageVector, val phase: Int?) {
    HOME(R.string.section_home, Icons.Filled.Home, 8),
    LIVE_TV(R.string.section_live_tv, Icons.Filled.PlayArrow, 7),
    GUIDE(R.string.section_guide, Icons.Filled.DateRange, 7),
    MOVIES(R.string.section_movies, Icons.Filled.Star, 8),
    SERIES(R.string.section_series, Icons.Filled.Menu, 8),
    FAVORITES(R.string.section_favorites, Icons.Filled.Favorite, 7),
    SEARCH(R.string.section_search, Icons.Filled.Search, 8),
    PLAYLISTS(R.string.section_playlists, Icons.Filled.AddCircle, null),
    SETTINGS(R.string.section_settings, Icons.Filled.Settings, null),
}

object ShellTags {
    const val ADD_SOURCE = "playlists-add-source"
    const val TEST_PROVIDER = "developer-add-test-provider"
    const val PLACEHOLDER_ITEMS = 6

    fun rail(section: Section) = "rail-${section.name}"

    fun item(section: Section, index: Int) = "item-${section.name}-$index"

    fun developerStream(id: String) = "developer-stream-$id"
}

/**
 * Main TV shell: a side navigation rail (tv-material `NavigationDrawer`, expands while focused) and the selected
 * section's placeholder content. Selecting a rail item with OK moves focus into that section's content.
 */
@Composable
fun MainShell(
    initialSection: Section = Section.HOME,
    onAddSource: () -> Unit,
    onPlayDeveloperStream: (String) -> Unit = {},
    onPlayChannel: (PlaylistId, ChannelScope, ChannelId) -> Unit = { _, _, _ -> },
    onSourceAdded: () -> Unit = {},
    onEditGuideLink: (PlaylistId) -> Unit = {},
    onOpenMovie: (PlaylistId, String) -> Unit = { _, _ -> },
    onOpenSeries: (PlaylistId, String) -> Unit = { _, _ -> },
    onPlayContent: (PlaylistId, ContentType, String) -> Unit = { _, _, _ -> },
) {
    var homeFirstKey by remember { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(initialSection) }
    val focusManager = LocalFocusManager.current
    var railHasFocus by remember { mutableStateOf(false) }
    var contentFocusRequests by remember { mutableIntStateOf(0) }
    val focus = rememberFocusMemory()
    val context = LocalContext.current
    val developerStreams = remember { DeveloperStreams.list(context) }

    BackHandler(enabled = !(railHasFocus && selected == Section.HOME)) {
        if (!railHasFocus) {
            focus.requestFocus(ShellTags.rail(selected))
        } else {
            selected = Section.HOME
            focus.requestFocus(ShellTags.rail(Section.HOME))
        }
    }

    NavigationDrawer(
        drawerContent = {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(horizontal = Tokens.space3, vertical = Tokens.safeVertical)
                    .selectableGroup()
                    // Entering the rail from content always lands on the selected section, not the nearest icon.
                    .focusProperties { onEnter = { focus.requester(ShellTags.rail(selected)).requestFocus() } }
                    .focusGroup()
                    .onFocusChanged { railHasFocus = it.hasFocus },
                verticalArrangement = Arrangement.spacedBy(Tokens.space2, Alignment.CenterVertically),
            ) {
                for (section in Section.entries) {
                    NavigationDrawerItem(
                        selected = selected == section,
                        onClick = {
                            selected = section
                            contentFocusRequests++
                        },
                        leadingContent = { Icon(section.icon, contentDescription = null) },
                        modifier = Modifier.rememberedFocus(focus, ShellTags.rail(section)),
                    ) {
                        Text(stringResource(section.title))
                    }
                }
            }
        },
    ) {
        // Keyed so each section starts with its own scroll and focus-restoration state.
        key(selected) {
            when (selected) {
                Section.LIVE_TV -> LiveTvSection(focus, favoritesOnly = false, onPlay = onPlayChannel, onAddSource = onAddSource)
                Section.FAVORITES -> LiveTvSection(focus, favoritesOnly = true, onPlay = onPlayChannel, onAddSource = onAddSource)
                Section.GUIDE -> GuideSection(focus, onPlay = onPlayChannel, onAddSource = onAddSource)
                Section.HOME -> HomeSection(
                    focus,
                    onPlayChannel = onPlayChannel,
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries,
                    onPlayContent = onPlayContent,
                    onFirstKey = { homeFirstKey = it },
                ) { SectionContent(selected, focus, onAddSource, onPlayDeveloperStream, onSourceAdded, onEditGuideLink) }
                Section.SEARCH -> SearchSection(
                    focus,
                    onPlayChannel = onPlayChannel,
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries,
                    onAddSource = onAddSource,
                )
                Section.MOVIES -> LibrarySection(focus, ImportUnit.MOVIES, onOpen = onOpenMovie, onAddSource = onAddSource)
                Section.SERIES -> LibrarySection(focus, ImportUnit.SERIES, onOpen = onOpenSeries, onAddSource = onAddSource)
                else -> SectionContent(selected, focus, onAddSource, onPlayDeveloperStream, onSourceAdded, onEditGuideLink)
            }
        }
    }

    LaunchedEffect(contentFocusRequests) {
        if (contentFocusRequests > 0) {
            val candidates = when (selected) {
                Section.PLAYLISTS -> listOf(ShellTags.ADD_SOURCE)
                Section.SETTINGS -> listOfNotNull(
                    developerStreams.firstOrNull()?.let { ShellTags.developerStream(it.id) },
                    ShellTags.item(selected, 0),
                )
                Section.LIVE_TV -> listOf(LiveTags.GROUP_ALL, LiveTags.emptyAddSource(favorites = false))
                Section.FAVORITES -> listOf(LiveTags.emptyAddSource(favorites = true))
                Section.GUIDE -> listOf(GuideTags.FIRST_CELL, GuideTags.ADD_SOURCE)
                Section.MOVIES, Section.SERIES -> listOf(LibraryTags.CATEGORY_ALL, LibraryTags.ADD_SOURCE)
                Section.HOME -> listOfNotNull(homeFirstKey, ShellTags.item(selected, 0))
                Section.SEARCH -> listOf(SearchTags.FIELD, SearchTags.ADD_SOURCE)
            }
            // Data-driven sections load asynchronously: wait briefly for their first element, then enter geometrically.
            repeat(20) {
                if (candidates.any { focus.requestFocus(it) }) return@LaunchedEffect
                delay(50)
            }
            focusManager.moveFocus(FocusDirection.Right)
        }
    }
    // First display: enter the selected section like selecting it in the rail; afterwards restore the last focus.
    LaunchedEffect(Unit) {
        if (focus.lastFocusedKey == null) contentFocusRequests++
    }
    if (focus.lastFocusedKey != null) RestoreFocusEffect(focus, ShellTags.rail(selected))
}

@Composable
private fun SectionContent(
    section: Section,
    focus: FocusMemory,
    onAddSource: () -> Unit,
    onPlayDeveloperStream: (String) -> Unit,
    onSourceAdded: () -> Unit,
    onEditGuideLink: (PlaylistId) -> Unit,
) {
    val body = section.phase?.let { stringResource(R.string.section_placeholder, it) } ?: stringResource(R.string.section_placeholder_later)
    // Playlists is a real screen since Phase 7; the placeholder line only belongs to sections still to come.
    PlaceholderPage(title = stringResource(section.title), body = if (section == Section.PLAYLISTS) null else body) {
        if (section == Section.PLAYLISTS) {
            ActionButton(stringResource(R.string.playlists_add_source), onAddSource, Modifier.rememberedFocus(focus, ShellTags.ADD_SOURCE))
            SourcesList(focus, onEditGuideLink)
            return@PlaceholderPage
        }
        if (section == Section.SETTINGS) {
            DeveloperStreamRow(focus, onPlayDeveloperStream, onSourceAdded)
        }
        // Returning to the row with Right restores the card that last had focus.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Tokens.space6),
            // Horizontal padding leaves room for the focus scale; the offset keeps cards aligned with the title.
            contentPadding = PaddingValues(horizontal = Tokens.space4, vertical = Tokens.space4),
            modifier = Modifier.padding(top = Tokens.space2).offset(x = -Tokens.space4).focusRestorer(),
        ) {
            items(count = ShellTags.PLACEHOLDER_ITEMS, key = { ShellTags.item(section, it) }) { index ->
                PlaceholderCard(
                    label = stringResource(R.string.placeholder_item, index + 1),
                    modifier = Modifier.rememberedFocus(focus, ShellTags.item(section, index)),
                )
            }
        }
    }
}

/** Debug builds only: synthetic test streams for trying the player with the remote (empty in release builds). */
@Composable
private fun DeveloperStreamRow(focus: FocusMemory, onPlay: (String) -> Unit, onSourceAdded: () -> Unit) {
    val context = LocalContext.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val streams = remember { DeveloperStreams.list(context) }
    if (streams.isEmpty()) return
    val testProvider = remember { DeveloperStreams.testProvider(context) }
    Text(stringResource(R.string.developer_streams_title), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
    Text(stringResource(R.string.developer_streams_body), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), contentPadding = PaddingValues(vertical = Tokens.space2)) {
        items(count = streams.size, key = { streams[it].id }) { index ->
            val stream = streams[index]
            ActionButton(stream.label, { onPlay(stream.id) }, Modifier.rememberedFocus(focus, ShellTags.developerStream(stream.id)))
        }
        if (testProvider != null) {
            item(key = "test-provider") {
                ActionButton(
                    stringResource(R.string.developer_add_test_provider),
                    {
                        scope.launch {
                            val (server, username, password) = testProvider
                            if (graph.addXtream("Test provider", server, username, password) is AddSourceResult.Added) onSourceAdded()
                        }
                    },
                    Modifier.rememberedFocus(focus, ShellTags.TEST_PROVIDER),
                )
            }
        }
    }
}

@Composable
private fun PlaceholderCard(label: String, modifier: Modifier) {
    Card(
        onClick = {},
        modifier = modifier.size(width = 196.dp, height = 110.dp),
        scale = CardDefaults.scale(focusedScale = Tokens.FOCUS_SCALE),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(Tokens.focusRingWidth, Tokens.focusRing))),
        colors = CardDefaults.colors(containerColor = Tokens.bgSurface1, focusedContainerColor = Tokens.bgSurface3),
    ) {
        Box(modifier = Modifier.padding(Tokens.space4)) {
            Text(text = label, style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
        }
    }
}
