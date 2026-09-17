package app.iptvplayer.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.FocusMemory
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
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzEmptyState
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay

/** Top-level sections (DESIGN_SYSTEM.md §2) with the roadmap phase that fills them, when planned. */
enum class Section(@param:StringRes val title: Int, val icon: ImageVector, val phase: Int?) {
    HOME(R.string.section_home, LuzIcons.Home, 8),
    LIVE_TV(R.string.section_live_tv, LuzIcons.LiveTv, 7),
    GUIDE(R.string.section_guide, LuzIcons.Guide, 7),
    MOVIES(R.string.section_movies, LuzIcons.Movies, 8),
    SERIES(R.string.section_series, LuzIcons.Series, 8),
    FAVORITES(R.string.section_favorites, LuzIcons.Favorites, 7),
    SEARCH(R.string.section_search, LuzIcons.Search, 8),
    SETTINGS(R.string.section_settings, LuzIcons.Settings, null),
}

object ShellTags {
    const val ADD_SOURCE = "home-add-source"

    fun rail(section: Section) = "rail-${section.name}"
}

/** Sections whose top is a picture that runs under the navigation; the rest start clear of it. */
private val FULL_BLEED = setOf(Section.HOME)

/**
 * The shell: every section fills the screen, and the navigation rail floats over its left edge (ADR-0034).
 *
 * The rail is always there as a slim strip of symbols and opens with names when the remote reaches it — by pressing
 * Left at the edge of the content, or Back. Choosing a section with OK drops the remote into it; Right leaves the rail
 * and returns to exactly where the viewer was. Back walks out one level at a time: the content, the rail, Home, and
 * then out of the app.
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
    var railFocused by remember { mutableStateOf(false) }
    var contentFocusRequests by remember { mutableIntStateOf(0) }
    val focusManager = LocalFocusManager.current
    val focus = rememberFocusMemory()
    // What last had focus in the content, so leaving the rail puts the remote back where it was.
    var lastContentKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focus) {
        snapshotFlow { focus.lastFocusedKey }.collect { key ->
            if (key != null && !key.startsWith(RAIL_PREFIX)) lastContentKey = key
        }
    }

    BackHandler(enabled = !(railFocused && selected == Section.HOME)) {
        if (!railFocused) {
            focus.requestFocus(ShellTags.rail(selected))
        } else {
            // In the rail, somewhere other than Home: Back goes Home and stays in the rail, so the viewer sees where
            // it took them. One more Back leaves the app.
            selected = Section.HOME
            focus.requestFocus(ShellTags.rail(Section.HOME))
        }
    }

    // The room every section sits in, painted once behind the rail and the content alike so there is no strip where
    // one ends: a faint glow from above settling into the environment colour. Home paints its own picture over it.
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Tokens.bgSurface1, ROOM_GLOW_END to Tokens.bgBase))) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (selected in FULL_BLEED) Modifier else Modifier.padding(start = Tokens.railCollapsedWidth)),
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
        NavigationRail(
            sections = Section.entries,
            selected = selected,
            focus = focus,
            onFocusChange = { railFocused = it },
            onLeave = {
                if (lastContentKey?.let { focus.requestFocus(it) } != true) contentFocusRequests++
            },
        ) { section ->
            selected = section
            contentFocusRequests++
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
            // The content is to the right of the rail. If nothing there takes focus, it stays on the rail rather than
            // going nowhere: a remote with nothing focused is a dead remote.
            if (!focusManager.moveFocus(FocusDirection.Right)) focus.requestFocus(ShellTags.rail(selected))
        }
    }
    // First display: enter the selected section like choosing it in the rail; afterwards restore the last focus.
    LaunchedEffect(Unit) {
        if (focus.lastFocusedKey == null) contentFocusRequests++
    }
    if (focus.lastFocusedKey != null) RestoreFocusEffect(focus, ShellTags.rail(selected))
}

@Composable
private fun NoSourceYet(focus: FocusMemory, onAddSource: () -> Unit) {
    // Home before there is anything to show (PRODUCT_DIRECTIVE.md §3): what Luz will be, and the one thing worth doing.
    LuzEmptyState(title = stringResource(R.string.home_empty_title), message = stringResource(R.string.home_empty_body)) {
        LuzButton(
            stringResource(R.string.playlists_add_source),
            onAddSource,
            Modifier.rememberedFocus(focus, ShellTags.ADD_SOURCE),
            kind = ButtonKind.PRIMARY,
            icon = LuzIcons.Add,
        )
    }
}

private const val RAIL_PREFIX = "rail-"
private const val ROOM_GLOW_END = 0.5f

/** How long entering a section waits for its first element: the screen may still be reading from the database. */
private const val ENTER_ATTEMPTS = 40
private const val ENTER_INTERVAL_MS = 50L
