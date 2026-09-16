package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.app.SearchResults
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.TvTextField
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.live.EmptyState
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay

object SearchTags {
    const val FIELD = "search-field"
    const val ADD_SOURCE = "search-add-source"
    const val STATUS = "search-status"

    fun result(row: String, id: String) = "search-$row-$id"
}

/**
 * Search (FR-SRCH-001/003, Phase 8 subset): one field; channels, movies and series of the current source update as you type
 * (150 ms after the last change), grouped by type. Everything is local. OK on a channel plays it, on a title opens its detail.
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

    Column(
        modifier = Modifier.fillMaxSize().padding(start = Tokens.space8, end = Tokens.safeHorizontal, top = Tokens.safeVertical),
        verticalArrangement = Arrangement.spacedBy(Tokens.space4),
    ) {
        TvTextField(
            stringResource(R.string.search_label),
            query,
            { query = it },
            Modifier.rememberedFocus(focus, SearchTags.FIELD),
            imeAction = ImeAction.Search,
        )
        when {
            query.isBlank() -> Status(stringResource(R.string.search_hint))
            found == null -> Unit
            found.channels.isEmpty() && found.movies.isEmpty() && found.series.isEmpty() -> Status(
                stringResource(R.string.search_nothing, query.trim()),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(Tokens.space4), modifier = Modifier.fillMaxSize()) {
                if (found.channels.isNotEmpty()) {
                    item(key = "channels") {
                        ResultRow(stringResource(R.string.search_channels)) {
                            items(found.channels.size, key = { found.channels[it].id.value }) { index ->
                                val channel = found.channels[index]
                                ResultCard(
                                    channel.name,
                                    channel.number?.toString(),
                                    channel.logo,
                                    resolver,
                                    220.dp,
                                    124.dp,
                                    Modifier.rememberedFocus(focus, SearchTags.result("channel", channel.id.value)),
                                ) {
                                    onPlayChannel(current, ChannelScope.All, channel.id)
                                }
                            }
                        }
                    }
                }
                if (found.movies.isNotEmpty()) {
                    item(key = "movies") {
                        ResultRow(stringResource(R.string.search_movies)) {
                            items(found.movies.size, key = { found.movies[it].id }) { index ->
                                val movie = found.movies[index]
                                ResultCard(
                                    movie.title,
                                    movie.year?.toString(),
                                    movie.poster,
                                    resolver,
                                    140.dp,
                                    210.dp,
                                    Modifier.rememberedFocus(focus, SearchTags.result("movie", movie.id)),
                                ) {
                                    onOpenMovie(current, movie.id)
                                }
                            }
                        }
                    }
                }
                if (found.series.isNotEmpty()) {
                    item(key = "series") {
                        ResultRow(stringResource(R.string.search_series)) {
                            items(found.series.size, key = { found.series[it].id }) { index ->
                                val series = found.series[index]
                                ResultCard(
                                    series.title,
                                    series.year?.toString(),
                                    series.poster,
                                    resolver,
                                    140.dp,
                                    210.dp,
                                    Modifier.rememberedFocus(focus, SearchTags.result("series", series.id)),
                                ) {
                                    onOpenSeries(current, series.id)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tokens.textSecondary, modifier = Modifier.testTag(SearchTags.STATUS))
}

@Composable
private fun ResultRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(Tokens.space4),
            contentPadding = PaddingValues(horizontal = Tokens.space4, vertical = Tokens.space4),
            modifier = Modifier.offset(x = -Tokens.space4).focusRestorer(),
            content = content,
        )
    }
}

@Composable
private fun ResultCard(
    title: String,
    caption: String?,
    artwork: UrlTemplate?,
    resolver: ((UrlTemplate) -> String?)?,
    width: Dp,
    height: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2), modifier = Modifier.width(width)) {
        Surface(
            onClick = onClick,
            modifier = modifier.width(width).height(height),
            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(Tokens.radiusSmall)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Tokens.bgSurface1, focusedContainerColor = Tokens.bgSurface3),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Tokens.focusRingWidth, Tokens.focusRing))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) { ArtworkImage(artwork, resolver, title, Modifier.fillMaxSize()) }
        }
        Text(title, style = MaterialTheme.typography.bodySmall, color = Tokens.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textTertiary, maxLines = 1) }
    }
}
