package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.app.SearchResults
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.TvTextField
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.EmptyState
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.CalmScrolling
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.LuzShelf
import app.iptvplayer.tv.ui.theme.LuzSkeletonShelf
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import app.iptvplayer.tv.ui.theme.luzLift
import kotlinx.coroutines.delay

object SearchTags {
    const val FIELD = "search-field"
    const val ADD_SOURCE = "search-add-source"
    const val STATUS = "search-status"

    fun result(row: String, id: String) = "search-$row-$id"

    fun key(label: String) = "search-key-$label"
}

/**
 * Search (FR-SRCH-001/003) in the reference app's manner: the query written large across the top, a strip of letters
 * under it that the remote types with, and the results beneath as shelves — channels, films, series — updating 150 ms
 * after each change. Everything is local.
 *
 * The strip is there because a system keyboard on a television covers the very results it is producing. The line
 * above is still a real text field, so OK on it opens the system keyboard for anyone who prefers it (or has a remote
 * with a keyboard or a microphone).
 */
@Composable
fun SearchSection(
    focus: FocusMemory,
    onPlayChannel: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    onOpenMovie: (PlaylistId, String) -> Unit,
    onOpenSeries: (PlaylistId, String) -> Unit,
    onAddSource: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchResults?>(null) }

    LaunchedEffect(revision) {
        playlist = graph.currentSource()?.playlistId
        loaded = true
    }
    val current = playlist
    LaunchedEffect(current, query, revision) {
        if (current == null || query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        delay(150)
        results = graph.search(current, query)
    }
    if (!loaded) return
    if (current == null) {
        EmptyState(
            stringResource(R.string.library_no_source),
            stringResource(R.string.playlists_add_source),
            focus,
            SearchTags.ADD_SOURCE,
            onAddSource,
        )
        return
    }
    val resolver = rememberArtworkResolver(current)
    val found = results

    CalmScrolling {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Tokens.shelfSpacing),
            contentPadding = PaddingValues(top = Tokens.space8, bottom = Tokens.space10),
        ) {
            item(key = "query") {
                Column(
                    modifier = Modifier.padding(start = Tokens.space6, end = Tokens.safeHorizontal),
                    verticalArrangement = Arrangement.spacedBy(Tokens.space3),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                        Icon(
                            LuzIcons.Search,
                            contentDescription = null,
                            tint = Tokens.textSecondary,
                            modifier = Modifier.padding(start = Tokens.space4).size(SEARCH_ICON),
                        )
                        TvTextField(
                            stringResource(R.string.search_label),
                            query,
                            { query = it },
                            Modifier.rememberedFocus(focus, SearchTags.FIELD),
                            imeAction = ImeAction.Search,
                            large = true,
                        )
                    }
                    LetterStrip(focus, onType = { query += it }, onDelete = { query = query.dropLast(1) })
                    Box(Modifier.fillMaxWidth().padding(top = Tokens.space2).height(1.dp).background(Tokens.hairline))
                }
            }
            when {
                query.isBlank() -> item(key = "status") { StatusLine(stringResource(R.string.search_hint)) }
                found == null -> item(key = "loading") { LuzSkeletonShelf(CardShape.POSTER, count = 5) }
                found.channels.isEmpty() && found.movies.isEmpty() && found.series.isEmpty() -> item(key = "status") {
                    StatusLine(stringResource(R.string.search_nothing, query.trim()))
                }
                else -> {
                    if (found.channels.isNotEmpty()) {
                        item(key = "channels") {
                            LuzShelf(stringResource(R.string.search_channels)) {
                                items(found.channels.size, key = { found.channels[it].id.value }) { index ->
                                    val channel = found.channels[index]
                                    LuzCard(
                                        title = channel.name,
                                        subtitle = channel.number?.toString(),
                                        shape = CardShape.LANDSCAPE,
                                        modifier = Modifier.rememberedFocus(focus, SearchTags.result("channel", channel.id.value)),
                                        onClick = { onPlayChannel(current, ChannelScope.All, channel.id) },
                                    ) { art -> ArtworkImage(channel.logo, resolver, channel.name, art, fit = true) }
                                }
                            }
                        }
                    }
                    if (found.movies.isNotEmpty()) {
                        item(key = "movies") {
                            LuzShelf(stringResource(R.string.search_movies)) {
                                items(found.movies.size, key = { found.movies[it].id }) { index ->
                                    val movie = found.movies[index]
                                    LuzCard(
                                        title = movie.title,
                                        subtitle = movie.year?.toString(),
                                        shape = CardShape.POSTER,
                                        modifier = Modifier.rememberedFocus(focus, SearchTags.result("movie", movie.id)),
                                        onClick = { onOpenMovie(current, movie.id) },
                                    ) { art -> ArtworkImage(movie.poster, resolver, movie.title, art) }
                                }
                            }
                        }
                    }
                    if (found.series.isNotEmpty()) {
                        item(key = "series") {
                            LuzShelf(stringResource(R.string.search_series)) {
                                items(found.series.size, key = { found.series[it].id }) { index ->
                                    val series = found.series[index]
                                    LuzCard(
                                        title = series.title,
                                        subtitle = series.year?.toString(),
                                        shape = CardShape.POSTER,
                                        modifier = Modifier.rememberedFocus(focus, SearchTags.result("series", series.id)),
                                        onClick = { onOpenSeries(current, series.id) },
                                    ) { art -> ArtworkImage(series.poster, resolver, series.title, art) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The letters, in one line, typed with the remote: the letter under the remote is a small white square, the rest are
 * grey. Space and delete sit at the ends, and 123 swaps the letters for digits.
 */
@Composable
private fun LetterStrip(focus: FocusMemory, onType: (String) -> Unit, onDelete: () -> Unit) {
    var digits by rememberSaveable { mutableStateOf(false) }
    val keys = if (digits) DIGITS else LETTERS
    LazyRow(
        modifier = Modifier.focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(KEY_GAP),
        contentPadding = PaddingValues(start = Tokens.space4, end = Tokens.space4, top = Tokens.space1, bottom = Tokens.space1),
    ) {
        item(key = "mode") {
            Key(if (digits) "abc" else "123", focus, SearchTags.key("mode"), wide = true) { digits = !digits }
        }
        item(key = "space") { Key(stringResource(R.string.search_key_space), focus, SearchTags.key("space"), wide = true) { onType(" ") } }
        items(keys.size, key = { keys[it] }) { index -> Key(keys[index], focus, SearchTags.key(keys[index])) { onType(keys[index]) } }
        item(key = "delete") { Key("\u232B", focus, SearchTags.key("delete"), wide = true) { onDelete() } }
    }
}

@Composable
private fun Key(label: String, focus: FocusMemory, tag: String, wide: Boolean = false, onPress: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .rememberedFocus(focus, tag)
            .luzLift(focused, RoundedCornerShape(Tokens.radiusSmall))
            .height(KEY_SIZE)
            .then(if (wide) Modifier else Modifier.width(KEY_WIDTH))
            .clip(RoundedCornerShape(Tokens.radiusSmall))
            .background(
                if (focused) {
                    Color.White
                } else if (wide) {
                    Tokens.raised
                } else {
                    Color.Transparent
                },
            )
            .luzClickable(onClick = onPress, onFocus = { focused = it })
            .padding(horizontal = if (wide) Tokens.space3 else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (wide) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleLarge,
            color = if (focused) Color.Black else Tokens.textSecondary,
        )
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Tokens.textTertiary,
        modifier = Modifier.padding(start = Tokens.contentStart - Tokens.railCollapsedWidth + Tokens.space4).testTag(SearchTags.STATUS),
    )
}

private val LETTERS = ('a'..'z').map { it.toString() }
private val DIGITS = ('0'..'9').map { it.toString() }

// Narrow enough that the whole alphabet, space and delete sit on one line beside the rail, as in the reference app.
private val KEY_SIZE = 30.dp
private val KEY_WIDTH = 22.dp
private val KEY_GAP = 1.dp
private val SEARCH_ICON = 26.dp
