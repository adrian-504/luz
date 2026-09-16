package app.iptvplayer.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import app.iptvplayer.tv.ui.settings.SettingsSection
import app.iptvplayer.tv.ui.settings.SettingsTags
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
    SETTINGS(R.string.section_settings, Icons.Filled.Settings, null),
}

object ShellTags {
    const val ADD_SOURCE = "home-add-source"

    fun rail(section: Section) = "rail-${section.name}"
}

/**
 * The shell: a line of sections across the top and the chosen one underneath (ADR-0033).
 *
 * Back steps out of the content to the bar, then to Home, then leaves the app — the same walk back the Apple TV app
 * has. Choosing a section with OK drops focus into it, so the bar is only ever passed through.
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

    Column(modifier = Modifier.fillMaxSize().background(Tokens.bgBase)) {
        TabBar(
            sections = Section.entries,
            selected = selected,
            focus = focus,
            modifier = Modifier.onFocusChanged { railHasFocus = it.hasFocus },
        ) { section ->
            selected = section
            contentFocusRequests++
        }
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
                ) { NoSourceYet(focus, onAddSource) }
                Section.SEARCH -> SearchSection(
                    focus,
                    onPlayChannel = onPlayChannel,
                    onOpenMovie = onOpenMovie,
                    onOpenSeries = onOpenSeries,
                    onAddSource = onAddSource,
                )
                Section.SETTINGS -> SettingsSection(focus, onAddSource, onEditGuideLink, onPlayDeveloperStream, onSourceAdded)
                Section.MOVIES -> LibrarySection(focus, ImportUnit.MOVIES, onOpen = onOpenMovie, onAddSource = onAddSource)
                Section.SERIES -> LibrarySection(focus, ImportUnit.SERIES, onOpen = onOpenSeries, onAddSource = onAddSource)
            }
        }
    }

    LaunchedEffect(contentFocusRequests) {
        if (contentFocusRequests > 0) {
            // Data-driven sections attach their first element a moment after the screen changes, and Home only knows its
            // first row once that row has composed — so the candidates are read again on every attempt, not once.
            repeat(ENTER_ATTEMPTS) {
                val candidates = when (selected) {
                    Section.SETTINGS -> listOf(SettingsTags.entry(SettingsTags.PROVIDERS), SettingsTags.ADD_SOURCE)
                    Section.LIVE_TV -> listOf(LiveTags.GROUP_ALL, LiveTags.emptyAddSource(favorites = false))
                    Section.FAVORITES -> listOf(LiveTags.emptyAddSource(favorites = true))
                    Section.GUIDE -> listOf(GuideTags.FIRST_CELL, GuideTags.ADD_SOURCE)
                    Section.MOVIES, Section.SERIES -> listOf(LibraryTags.CATEGORY_ALL, LibraryTags.ADD_SOURCE)
                    Section.HOME -> listOfNotNull(homeFirstKey, ShellTags.ADD_SOURCE)
                    Section.SEARCH -> listOf(SearchTags.FIELD, SearchTags.ADD_SOURCE)
                }
                if (candidates.any { focus.requestFocus(it) }) return@LaunchedEffect
                delay(ENTER_INTERVAL_MS)
            }
            // The bar is across the top, so the content is below it: moving right would only walk to the next section.
            if (!focusManager.moveFocus(FocusDirection.Down)) {
                // Nothing down there took it. Focus goes back to the bar rather than nowhere: a remote with no focus is
                // a dead remote, and the viewer would have to leave the app to recover.
                focus.requestFocus(ShellTags.rail(selected))
            }
        }
    }
    // First display: enter the selected section like selecting it in the rail; afterwards restore the last focus.
    LaunchedEffect(Unit) {
        if (focus.lastFocusedKey == null) contentFocusRequests++
    }
    if (focus.lastFocusedKey != null) RestoreFocusEffect(focus, ShellTags.rail(selected))
}

@Composable
private fun NoSourceYet(focus: FocusMemory, onAddSource: () -> Unit) {
    // Home before there is anything to show (PRODUCT_DIRECTIVE.md §3): the welcome, and the one thing worth doing.
    PlaceholderPage(title = stringResource(R.string.home_empty_title), body = stringResource(R.string.home_empty_body)) {
        ActionButton(
            stringResource(R.string.playlists_add_source),
            onAddSource,
            Modifier.rememberedFocus(focus, ShellTags.ADD_SOURCE),
            primary = true,
        )
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

/** How long entering a section waits for its first element: the screen may still be reading from the database. */
private const val ENTER_ATTEMPTS = 40
private const val ENTER_INTERVAL_MS = 50L
