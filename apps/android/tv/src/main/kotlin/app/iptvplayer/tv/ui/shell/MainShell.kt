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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import app.iptvplayer.tv.R
import app.iptvplayer.tv.developer.DeveloperStreams
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.PlaceholderPage
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens

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
fun MainShell(onAddSource: () -> Unit, onPlayDeveloperStream: (String) -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf(Section.HOME) }
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
            SectionContent(section = selected, focus = focus, onAddSource = onAddSource, onPlayDeveloperStream = onPlayDeveloperStream)
        }
    }

    LaunchedEffect(contentFocusRequests) {
        if (contentFocusRequests > 0) {
            val first = when (selected) {
                Section.PLAYLISTS -> ShellTags.ADD_SOURCE
                Section.SETTINGS -> developerStreams.firstOrNull()?.let { ShellTags.developerStream(it.id) } ?: ShellTags.item(selected, 0)
                else -> ShellTags.item(selected, 0)
            }
            focus.requestFocus(first)
        }
    }
    RestoreFocusEffect(focus, ShellTags.item(Section.HOME, 0))
}

@Composable
private fun SectionContent(section: Section, focus: FocusMemory, onAddSource: () -> Unit, onPlayDeveloperStream: (String) -> Unit) {
    val body = section.phase?.let { stringResource(R.string.section_placeholder, it) } ?: stringResource(R.string.section_placeholder_later)
    PlaceholderPage(title = stringResource(section.title), body = body) {
        if (section == Section.PLAYLISTS) {
            ActionButton(stringResource(R.string.playlists_add_source), onAddSource, Modifier.rememberedFocus(focus, ShellTags.ADD_SOURCE))
        }
        if (section == Section.SETTINGS) {
            DeveloperStreamRow(focus, onPlayDeveloperStream)
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
private fun DeveloperStreamRow(focus: FocusMemory, onPlay: (String) -> Unit) {
    val context = LocalContext.current
    val streams = remember { DeveloperStreams.list(context) }
    if (streams.isEmpty()) return
    Text(stringResource(R.string.developer_streams_title), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
    Text(stringResource(R.string.developer_streams_body), style = MaterialTheme.typography.bodySmall, color = Tokens.textTertiary)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), contentPadding = PaddingValues(vertical = Tokens.space2)) {
        items(count = streams.size, key = { streams[it].id }) { index ->
            val stream = streams[index]
            ActionButton(stream.label, { onPlay(stream.id) }, Modifier.rememberedFocus(focus, ShellTags.developerStream(stream.id)))
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
