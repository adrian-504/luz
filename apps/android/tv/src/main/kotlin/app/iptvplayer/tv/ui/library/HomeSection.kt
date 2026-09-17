package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
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
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.CalmScrolling
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzHero
import app.iptvplayer.tv.ui.theme.LuzIconButton
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.LuzShelf
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    const val HERO_PLAY = "home-hero-play"
    const val HERO_FAVORITE = "home-hero-favorite"
    const val HERO_INFO = "home-hero-info"
    const val HERO_NEXT = "home-hero-next"
}

/** A card on a Home row, and what the hero needs to feature it. */
private data class HomeCard(
    val key: String,
    val title: String,
    val caption: String?,
    val poster: UrlTemplate?,
    val fraction: Float?,
    /** Wider artwork for the hero when the provider has it; the poster stands in when it does not. */
    val backdrop: UrlTemplate? = null,
    val plot: String? = null,
    /** What this is, for the hero's metadata and actions; null for a channel. */
    val type: ContentType? = null,
    val facts: List<String> = emptyList(),
    val favorite: Boolean = false,
    /** Plays it (a film, an episode to resume) or, for a series, opens it. */
    val open: () -> Unit,
    /** Opens its detail page, where there is one. */
    val details: (() -> Unit)? = null,
)

private data class HomeRow(val id: String, val title: Int, val cards: List<HomeCard>, val landscape: Boolean = false)

/**
 * What a Home row is made of, before it is loaded. The rows are described rather than written out one after another so
 * that the order — and the viewer's own choice of which rows to show (PRODUCT_DIRECTIVE.md §3) — is data.
 */
private data class HomeRowSpec(
    val id: String,
    val title: Int,
    val landscape: Boolean = false,
    val load: suspend (PlaylistId) -> List<HomeCard>,
)

/**
 * Home, in the reference app's composition (DESIGN_SYSTEM.md §9): a featured title filling most of the screen, and the
 * shelves beneath it.
 *
 * The hero is a slow carousel of what is worth watching — what the viewer is part-way through first, then recently
 * added films and series that have wide artwork. It moves on by itself only while the remote is elsewhere, and never
 * while the viewer is reading it. The whole room takes its colour from the featured picture. Shelves with nothing on
 * them are left out. [placeholder] is shown when there is no source, and [onFirstKey] tells the shell where the remote
 * lands when Home opens: on the hero's first action.
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
    val movieKind = stringResource(R.string.home_kind_movie)
    val seriesKind = stringResource(R.string.home_kind_series)

    LaunchedEffect(revision, watched) {
        val source = graph.currentSource()
        playlist = source?.playlistId
        val id = source?.playlistId
        val specs = listOf(
            HomeRowSpec(HomeTags.CONTINUE, R.string.home_continue, landscape = true) { p ->
                graph.continueCards(p, ROW_LIMIT).map { card ->
                    HomeCard(
                        key = card.id,
                        title = card.title,
                        caption = card.subtitle,
                        poster = card.poster,
                        fraction = card.fraction,
                        type = card.type,
                        facts = listOfNotNull(card.subtitle),
                        open = { onPlayContent(p, card.type, card.id) },
                        details = if (card.type == ContentType.MOVIE) ({ onOpenMovie(p, card.id) }) else null,
                    )
                }
            },
            // Favourites first, with what is on them now; a viewer with no favourites yet still gets their channels.
            HomeRowSpec(HomeTags.CHANNELS, R.string.home_favorite_channels, landscape = true) { p ->
                channelCards(graph, p, favorites = true, onPlay = onPlayChannel)
            },
            HomeRowSpec(HomeTags.LIVE, R.string.home_live_now, landscape = true) { p ->
                channelCards(graph, p, favorites = false, onPlay = onPlayChannel)
            },
            HomeRowSpec(HomeTags.MOVIES, R.string.home_recent_movies) { p ->
                graph.recentMovies(p, ROW_LIMIT).map { movie ->
                    HomeCard(
                        key = movie.id,
                        title = movie.title,
                        caption = movie.year?.toString(),
                        poster = movie.poster,
                        fraction = null,
                        backdrop = movie.backdrop,
                        plot = movie.plot,
                        type = ContentType.MOVIE,
                        facts = listOfNotNull(movieKind, movie.genres.firstOrNull(), movie.year?.toString(), movie.rating),
                        favorite = movie.isFavorite,
                        open = { onPlayContent(p, ContentType.MOVIE, movie.id) },
                        details = { onOpenMovie(p, movie.id) },
                    )
                }
            },
            HomeRowSpec(HomeTags.SERIES, R.string.home_series) { p ->
                graph.series(p, null, ROW_LIMIT, 0).map { series ->
                    HomeCard(
                        key = series.id,
                        title = series.title,
                        caption = series.year?.toString(),
                        poster = series.poster,
                        fraction = null,
                        backdrop = series.backdrop,
                        plot = series.plot,
                        type = ContentType.SERIES,
                        facts = listOfNotNull(seriesKind, series.genres.firstOrNull(), series.year?.toString(), series.rating),
                        favorite = series.isFavorite,
                        open = { onOpenSeries(p, series.id) },
                        details = { onOpenSeries(p, series.id) },
                    )
                }
            },
        )
        val chosen = graph.homeRows()
        rows = if (id == null) {
            emptyList()
        } else {
            // The viewer's own choice of rows and their order, when they have made one; otherwise Home's own order.
            val wanted = chosen?.mapNotNull { key -> specs.firstOrNull { it.id == key } } ?: specs
            val loaded = wanted.map { spec -> HomeRow(spec.id, spec.title, spec.load(id), spec.landscape) }
                .filter { it.cards.isNotEmpty() }
            // "Live now" is there for a viewer with no favourites yet; once they have some, the favourites row says it better.
            if (loaded.any { it.id == HomeTags.CHANNELS }) loaded.filterNot { it.id == HomeTags.LIVE } else loaded
        }
        onFirstKey(rows?.takeIf { it.isNotEmpty() }?.let { HomeTags.HERO_PLAY })
    }

    val shown = rows ?: return
    if (shown.isEmpty()) {
        placeholder()
        return
    }
    val resolver = rememberArtworkResolver(playlist)
    val featured = remember(shown) { featuredFrom(shown) }
    var page by remember(featured) { mutableIntStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    val hero = featured.getOrNull(page) ?: featured.first()
    val ambient = rememberAmbientColor(hero.backdrop ?: hero.poster, resolver)
    val scope = rememberCoroutineScope()

    // The carousel turns slowly on its own, but never while the viewer is reading it.
    LaunchedEffect(featured, heroFocused, page) {
        if (featured.size < 2 || heroFocused) return@LaunchedEffect
        delay(CAROUSEL_INTERVAL_MS)
        page = (page + 1) % featured.size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to ambient, Tokens.HERO_HEIGHT_FRACTION to ambient, 1f to Tokens.bgBase)),
    ) {
        CalmScrolling {
            // No content padding: the hero's height is a share of the list's viewport, and padding would shrink it.
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Tokens.shelfSpacing),
            ) {
                item(key = "hero") {
                    LuzHero(
                        title = hero.title,
                        meta = hero.facts,
                        detail = hero.plot,
                        // A share of the list's own height, not of the "screen height" the platform reports: some
                        // televisions report a smaller one than they draw, and the hero came out half the size.
                        modifier = Modifier.fillMaxWidth().fillParentMaxHeight(Tokens.HERO_HEIGHT_FRACTION).onFocusChanged {
                            heroFocused = it.hasFocus
                        },
                        room = ambient,
                        page = page,
                        pages = featured.size,
                        artworkOf = hero,
                        actions = {
                            HeroActions(
                                card = hero,
                                focus = focus,
                                canAdvance = featured.size > 1,
                                onFavorite = {
                                    hero.type?.let { type -> scope.launch { graph.setFavorite(type, hero.key, !hero.favorite) } }
                                },
                                onNext = { page = (page + 1) % featured.size },
                            )
                        },
                    ) { card, modifier ->
                        ArtworkImage(card.backdrop ?: card.poster, resolver, null, modifier, BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX)
                    }
                }
                items(shown.size, key = { shown[it].id }) { index ->
                    val row = shown[index]
                    LuzShelf(title = stringResource(row.title)) {
                        items(row.cards.size, key = { row.cards[it].key }) { cardIndex ->
                            val card = row.cards[cardIndex]
                            val channel = row.id == HomeTags.CHANNELS || row.id == HomeTags.LIVE
                            LuzCard(
                                title = card.title,
                                subtitle = card.caption,
                                shape = if (row.landscape) CardShape.LANDSCAPE else CardShape.POSTER,
                                progress = card.fraction,
                                modifier = Modifier.rememberedFocus(focus, HomeTags.item(row.id, card.key)),
                                onClick = card.open,
                            ) { modifier ->
                                ArtworkImage(card.poster, resolver, card.title.takeIf { channel }, modifier, fit = channel)
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(Tokens.space16)) }
            }
        }
    }
}

/**
 * The hero's buttons: Play (or Resume, or Episodes for a series), then favourite and more information as round buttons,
 * then Next when there is more than one featured title.
 */
@Composable
private fun RowScope.HeroActions(card: HomeCard, focus: FocusMemory, canAdvance: Boolean, onFavorite: () -> Unit, onNext: () -> Unit) {
    val primary = when {
        card.type == ContentType.SERIES -> stringResource(R.string.home_hero_open)
        card.fraction != null && card.fraction > 0f -> stringResource(R.string.home_hero_resume)
        else -> stringResource(R.string.home_hero_play)
    }
    LuzButton(primary, card.open, Modifier.rememberedFocus(focus, HomeTags.HERO_PLAY), kind = ButtonKind.PRIMARY, icon = LuzIcons.Play)
    if (card.type == ContentType.MOVIE || card.type == ContentType.SERIES) {
        LuzIconButton(
            if (card.favorite) LuzIcons.Check else LuzIcons.Add,
            stringResource(if (card.favorite) R.string.home_hero_unfavorite else R.string.home_hero_favorite),
            onFavorite,
            Modifier.rememberedFocus(focus, HomeTags.HERO_FAVORITE),
        )
    }
    card.details?.let {
        LuzIconButton(LuzIcons.Info, stringResource(R.string.home_hero_info), it, Modifier.rememberedFocus(focus, HomeTags.HERO_INFO))
    }
    if (canAdvance) {
        LuzIconButton(
            LuzIcons.Chevron,
            stringResource(R.string.home_hero_next),
            onNext,
            Modifier.rememberedFocus(focus, HomeTags.HERO_NEXT),
        )
    }
}

/**
 * What the hero features, in order: what the viewer is part-way through, then titles with wide artwork, then anything
 * with a picture at all. Channels never feature — a logo is not a hero.
 */
private fun featuredFrom(rows: List<HomeRow>): List<HomeCard> {
    val titles = rows.filter { it.id != HomeTags.CHANNELS && it.id != HomeTags.LIVE }.flatMap { it.cards }
    val ordered = titles.filter { it.fraction != null } + titles.filter { it.backdrop != null } + titles.filter { it.poster != null }
    return ordered.distinctBy { it.key }.take(FEATURED_LIMIT).ifEmpty { rows.first().cards.take(1) }
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
            key = channel.id.value,
            title = channel.name,
            caption = guide[channel.id.value]?.current?.title,
            poster = channel.logo,
            fraction = null,
            open = { onPlay(playlist, scope, channel.id) },
        )
    }
}

private const val ROW_LIMIT = 20
private const val FEATURED_LIMIT = 6
private const val CAROUSEL_INTERVAL_MS = 9_000L
