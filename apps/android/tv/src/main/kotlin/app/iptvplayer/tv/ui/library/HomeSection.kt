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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
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
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens

object HomeTags {
    fun item(row: String, id: String) = "home-$row-$id"

    const val CONTINUE = "continue"
    const val CHANNELS = "channels"
    const val MOVIES = "movies"
    const val SERIES = "series"
}

/** A card on a Home row. */
private data class HomeCard(
    val key: String,
    val title: String,
    val caption: String?,
    val poster: UrlTemplate?,
    val fraction: Float?,
    val open: () -> Unit,
)

private data class HomeRow(val id: String, val title: Int, val cards: List<HomeCard>, val wide: Boolean = false)

/**
 * Home (FR-HOME-001, Android subset for Phase 8): Continue watching, favorite channels, recently added movies and series of the
 * current source. Rows without content are left out. [placeholder] is shown when there is no source or nothing to show yet.
 * Returns the focus key of the first card so the shell can move focus into Home.
 */
@Composable
fun HomeSection(
    focus: FocusMemory,
    onPlayChannel: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    onOpenMovie: (PlaylistId, String) -> Unit,
    onOpenSeries: (PlaylistId, String) -> Unit,
    onPlayContent: (PlaylistId, ContentType, String) -> Unit,
    onFirstKey: (String?) -> Unit,
    placeholder: @Composable () -> Unit,
) {
    val graph = LocalAppGraph.current
    val revision by graph.revision.collectAsState()
    val watched by graph.watchRevision.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var rows by remember { mutableStateOf<List<HomeRow>?>(null) }

    LaunchedEffect(revision, watched) {
        val source = graph.currentSource()
        playlist = source?.playlistId
        val id = source?.playlistId
        rows = if (id == null) {
            emptyList()
        } else {
            listOf(
                HomeRow(
                    HomeTags.CONTINUE,
                    R.string.home_continue,
                    graph.continueCards(id, 20).map { card ->
                        HomeCard(card.id, card.title, card.subtitle, card.poster, card.fraction) { onPlayContent(id, card.type, card.id) }
                    },
                ),
                HomeRow(
                    HomeTags.CHANNELS,
                    R.string.home_favorite_channels,
                    graph.favoriteChannels(id).take(20).map { channel ->
                        HomeCard(channel.id.value, channel.name, channel.number?.toString(), channel.logo, null) {
                            onPlayChannel(id, ChannelScope.Favorites, channel.id)
                        }
                    },
                    wide = true,
                ),
                HomeRow(
                    HomeTags.MOVIES,
                    R.string.home_recent_movies,
                    graph.recentMovies(id, 20).map { movie ->
                        HomeCard(movie.id, movie.title, movie.year?.toString(), movie.poster, null) { onOpenMovie(id, movie.id) }
                    },
                ),
                HomeRow(
                    HomeTags.SERIES,
                    R.string.home_series,
                    graph.series(id, null, 20, 0).map { series ->
                        HomeCard(series.id, series.title, series.year?.toString(), series.poster, null) { onOpenSeries(id, series.id) }
                    },
                ),
            ).filter { it.cards.isNotEmpty() }
        }
        onFirstKey(rows?.firstOrNull()?.let { HomeTags.item(it.id, it.cards.first().key) })
    }

    val shown = rows ?: return
    if (shown.isEmpty()) {
        placeholder()
        return
    }
    val resolver = rememberArtworkResolver(playlist)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = Tokens.space8, top = Tokens.safeVertical, bottom = Tokens.safeVertical),
        verticalArrangement = Arrangement.spacedBy(Tokens.space6),
    ) {
        items(shown.size, key = { shown[it].id }) { index ->
            val row = shown[index]
            Column {
                Text(stringResource(row.title), style = MaterialTheme.typography.titleLarge, color = Tokens.textPrimary)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.space4),
                    contentPadding = PaddingValues(horizontal = Tokens.space4, vertical = Tokens.space4),
                    modifier = Modifier.offset(x = -Tokens.space4).focusRestorer(),
                ) {
                    items(row.cards.size, key = { row.cards[it].key }) { cardIndex ->
                        val card = row.cards[cardIndex]
                        HomeCardView(
                            card,
                            resolver,
                            if (row.wide) 220.dp else 140.dp,
                            if (row.wide) 124.dp else 210.dp,
                            Modifier.rememberedFocus(focus, HomeTags.item(row.id, card.key)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeCardView(card: HomeCard, resolver: ((UrlTemplate) -> String?)?, width: Dp, height: Dp, modifier: Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2), modifier = Modifier.width(width)) {
        Surface(
            onClick = card.open,
            modifier = modifier.width(width).height(height),
            shape = ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(Tokens.radiusSmall)),
            colors = ClickableSurfaceDefaults.colors(containerColor = Tokens.bgSurface1, focusedContainerColor = Tokens.bgSurface3),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(Tokens.focusRingWidth, Tokens.focusRing))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize()) {
                ArtworkImage(card.poster, resolver, card.title, Modifier.fillMaxSize())
                WatchedBar(card.fraction, Modifier.align(Alignment.BottomStart))
            }
        }
        Text(
            card.title,
            style = MaterialTheme.typography.bodySmall,
            color = Tokens.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        card.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Tokens.textTertiary, maxLines = 1) }
    }
}
