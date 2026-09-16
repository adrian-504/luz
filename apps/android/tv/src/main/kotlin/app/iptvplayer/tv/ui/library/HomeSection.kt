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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
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
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.live.ChannelScope
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzHero
import app.iptvplayer.tv.ui.theme.Tokens
import kotlin.time.Clock

/** Every row Home can show, in its default order — the list Settings offers the viewer to choose from. */
val HOME_ROW_TITLES: List<Pair<String, Int>> = listOf(
    HomeTags.CONTINUE to R.string.home_continue,
    HomeTags.CHANNELS to R.string.home_favorite_channels,
    HomeTags.LIVE to R.string.home_live_now,
    HomeTags.MOVIES to R.string.home_recent_movies,
    HomeTags.SERIES to R.string.home_series,
)

object HomeTags {
    fun item(row: String, id: String) = "home-$row-$id"

    const val CONTINUE = "continue"
    const val CHANNELS = "channels"
    const val LIVE = "live"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val GREETING = "home-greeting"
}

/** A card on a Home row. */
private data class HomeCard(
    val key: String,
    val title: String,
    val caption: String?,
    val poster: UrlTemplate?,
    val fraction: Float?,
    /** Wider artwork for the hero when the provider has it; the poster stands in when it does not. */
    val backdrop: UrlTemplate? = null,
    val open: () -> Unit,
)

private data class HomeRow(val id: String, val title: Int, val cards: List<HomeCard>, val wide: Boolean = false)

/**
 * What a Home row is made of, before it is loaded. The rows are described rather than written out one after another so
 * that the order — and later the viewer's own choice of which rows to show (PRODUCT_DIRECTIVE.md §3) — is data.
 */
private data class HomeRowSpec(val id: String, val title: Int, val wide: Boolean = false, val load: suspend (PlaylistId) -> List<HomeCard>)

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
        val specs = listOf(
            HomeRowSpec(HomeTags.CONTINUE, R.string.home_continue) { p ->
                graph.continueCards(p, ROW_LIMIT).map { card ->
                    HomeCard(card.id, card.title, card.subtitle, card.poster, card.fraction) { onPlayContent(p, card.type, card.id) }
                }
            },
            // Favourites first, with what is on them now; a viewer with no favourites yet still gets their channels.
            HomeRowSpec(HomeTags.CHANNELS, R.string.home_favorite_channels, wide = true) { p ->
                channelCards(graph, p, favorites = true, onPlay = onPlayChannel)
            },
            HomeRowSpec(HomeTags.LIVE, R.string.home_live_now, wide = true) { p ->
                channelCards(graph, p, favorites = false, onPlay = onPlayChannel)
            },
            HomeRowSpec(HomeTags.MOVIES, R.string.home_recent_movies) { p ->
                graph.recentMovies(p, ROW_LIMIT).map { movie ->
                    HomeCard(movie.id, movie.title, movie.year?.toString(), movie.poster, null, movie.backdrop) {
                        onOpenMovie(p, movie.id)
                    }
                }
            },
            HomeRowSpec(HomeTags.SERIES, R.string.home_series) { p ->
                graph.series(p, null, ROW_LIMIT, 0).map { series ->
                    HomeCard(series.id, series.title, series.year?.toString(), series.poster, null, series.backdrop) {
                        onOpenSeries(p, series.id)
                    }
                }
            },
        )
        val chosen = graph.homeRows()
        rows = if (id == null) {
            emptyList()
        } else {
            // The viewer's own choice of rows and their order, when they have made one; otherwise Home's own order.
            val wanted = chosen?.mapNotNull { key -> specs.firstOrNull { it.id == key } } ?: specs
            val loaded = wanted.map { spec -> HomeRow(spec.id, spec.title, spec.load(id), spec.wide) }
                .filter { it.cards.isNotEmpty() }
            // "Live now" is there for a viewer with no favourites yet; once they have some, the favourites row says it better.
            if (loaded.any { it.id == HomeTags.CHANNELS }) loaded.filterNot { it.id == HomeTags.LIVE } else loaded
        }
        onFirstKey(rows?.firstOrNull()?.let { HomeTags.item(it.id, it.cards.first().key) })
    }

    val shown = rows ?: return
    if (shown.isEmpty()) {
        placeholder()
        return
    }
    val resolver = rememberArtworkResolver(playlist)
    // What the hero shows follows the card under focus, so the top of the screen answers "what is this?" as you move.
    var featured by remember(shown) { mutableStateOf(shown.first().cards.first() to shown.first()) }

    // The hero scrolls with the shelves rather than staying pinned: a pinned one leaves the captions of a half-scrolled
    // shelf stranded underneath it. At this size the hero and the first shelf both fit without scrolling, so the screen
    // still opens on the artwork.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Tokens.space4),
        contentPadding = PaddingValues(bottom = Tokens.safeVertical),
    ) {
        item(key = HomeTags.GREETING) {
            val (heroCard, heroRow) = featured
            Box {
                LuzHero(
                    title = heroCard.title,
                    subtitle = heroCard.caption,
                    detail = stringResource(heroRow.title),
                    artworkOf = heroCard,
                ) { card, modifier -> ArtworkImage(card.backdrop ?: card.poster, resolver, null, modifier) }
                Text(
                    greeting(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tokens.textSecondary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = Tokens.space8, top = Tokens.safeVertical)
                        .testTag(HomeTags.GREETING),
                )
            }
        }
        items(shown.size, key = { shown[it].id }) { index ->
            val row = shown[index]
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                Text(
                    stringResource(row.title),
                    style = MaterialTheme.typography.labelLarge,
                    color = Tokens.textSecondary,
                    modifier = Modifier.padding(start = Tokens.space8),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Tokens.space3),
                    contentPadding = PaddingValues(horizontal = Tokens.space8, vertical = Tokens.space2),
                    modifier = Modifier.focusRestorer(),
                ) {
                    items(row.cards.size, key = { row.cards[it].key }) { cardIndex ->
                        val card = row.cards[cardIndex]
                        LuzCard(
                            title = card.title,
                            subtitle = card.caption,
                            shape = if (row.wide) CardShape.WIDE else CardShape.POSTER,
                            progress = card.fraction,
                            modifier = Modifier
                                .rememberedFocus(focus, HomeTags.item(row.id, card.key))
                                .onFocusChanged { if (it.isFocused) featured = card to row },
                            onClick = card.open,
                        ) { modifier -> ArtworkImage(card.poster, resolver, null, modifier) }
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
                // The name is written under the card already, so an image-less card stays quiet rather than repeating it.
                ArtworkImage(card.poster, resolver, null, Modifier.fillMaxSize())
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

/** Channel cards with what is on now underneath: the viewer's favourites, or the first channels if they have none yet. */
private suspend fun channelCards(
    graph: AppGraph,
    playlist: PlaylistId,
    favorites: Boolean,
    onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit,
): List<HomeCard> {
    val channels = if (favorites) {
        graph.favoriteChannels(playlist).take(ROW_LIMIT)
    } else {
        graph.channels(playlist, null).take(ROW_LIMIT)
    }
    if (channels.isEmpty()) return emptyList()
    val scope = if (favorites) ChannelScope.Favorites else ChannelScope.All
    val guide = graph.storedNowNext(playlist, channels, Clock.System.now())
    return channels.map { channel ->
        HomeCard(
            channel.id.value,
            channel.name,
            guide[channel.id.value]?.current?.title,
            channel.logo,
            null,
        ) { onPlay(playlist, scope, channel.id) }
    }
}

private const val ROW_LIMIT = 20

/** "Good evening" and its neighbours, by the TV's own clock (PRODUCT_DIRECTIVE.md §3). */
@Composable
private fun greeting(): String = stringResource(
    when (java.time.LocalTime.now().hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..17 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    },
)
