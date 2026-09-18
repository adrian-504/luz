package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.FocusMemory
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
import app.iptvplayer.tv.ui.theme.LuzSkeletonShelf
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import app.iptvplayer.tv.ui.theme.luzLift
import app.iptvplayer.tv.ui.theme.revealsListTop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a full grid of films or shows is filtered by, written as a short string so the choice survives leaving the screen:
 * everything, one of the provider's categories, a genre, or a decade.
 */
object Browse {
    const val ALL = "all"

    fun category(id: String) = "category:$id"

    fun genre(name: String) = "genre:$name"

    fun decade(start: Int) = "decade:$start"

    fun group(id: String) = "mine:$id"
}

/** A title on a shelf, whichever kind it is. */
private data class ShelfItem(
    val id: String,
    val title: String,
    val caption: String?,
    val poster: UrlTemplate?,
    val backdrop: UrlTemplate?,
    val plot: String?,
    val facts: List<String>,
    val progress: Float?,
    val favorite: Boolean,
    val year: Int? = null,
)

/** A tile that opens a full grid: a genre, a decade, a category. */
private data class BrowseTile(val key: String, val title: String, val count: Long?)

private sealed interface Shelf {
    val key: String
    val title: String

    data class Titles(override val key: String, override val title: String, val items: List<ShelfItem>) : Shelf

    data class Tiles(override val key: String, override val title: String, val tiles: List<BrowseTile>) : Shelf
}

/**
 * Movies or Series as the reference app lays them out (ADR-0035): a featured title across the top, then shelves Luz
 * builds itself — recently added or new episodes, popular, highest rated, "because you watched", My List, the largest
 * genres — then tiles for every genre and decade, and at the very bottom the provider's own categories. A tile opens
 * the full grid for it ([onBrowse]); a title opens its page ([onOpen]).
 *
 * Genres, decades and ratings come from each title's page, which Luz fetches in the background, so these shelves fill in
 * over the first hours with a new provider; the recent shelf and the categories are there from the start.
 */
@Composable
fun LibraryShelves(
    focus: FocusMemory,
    playlist: PlaylistId,
    unit: ImportUnit,
    onOpen: (String) -> Unit,
    onPlay: (String) -> Unit,
    onBrowse: (String) -> Unit,
    onFirstKey: (String?) -> Unit = {},
    returnTo: String? = null,
    onReturned: () -> Unit = {},
) {
    val graph = LocalAppGraph.current
    val resources = LocalResources.current
    val revision by graph.revision.collectAsState()
    val watched by graph.watchRevision.collectAsState()
    val details by graph.detailRevision.collectAsState()
    val detailsStep = details / SHELF_REFRESH_STEP
    val lists by graph.listRevision.collectAsState()
    var shelves by remember { mutableStateOf<List<Shelf>?>(null) }
    val movies = unit == ImportUnit.MOVIES

    LaunchedEffect(playlist, unit, revision, watched, detailsStep, lists) {
        fun movie(row: MovieRow) = ShelfItem(
            row.id,
            row.title,
            row.year?.toString(),
            row.poster,
            row.backdrop,
            row.plot,
            listOfNotNull(row.year?.toString(), row.genres.firstOrNull(), row.rating?.let { "★ $it" }),
            row.progress?.takeIf { !it.completed }?.fraction,
            row.isFavorite,
            row.year,
        )
        fun series(row: SeriesRow) = ShelfItem(
            row.id,
            row.title,
            row.year?.toString(),
            row.poster,
            row.backdrop,
            row.plot,
            listOfNotNull(row.year?.toString(), row.genres.firstOrNull(), row.rating?.let { "★ $it" }),
            null,
            row.isFavorite,
            row.year,
        )
        fun titles(key: String, title: Int, items: List<ShelfItem>) = Shelf.Titles(key, resources.getString(title), items)
        val built = mutableListOf<Shelf>()
        fun add(shelf: Shelf) {
            val empty = when (shelf) {
                is Shelf.Titles -> shelf.items.isEmpty()
                is Shelf.Tiles -> shelf.tiles.isEmpty()
            }
            if (empty) return
            val personal = shelf.key == "continue" || shelf.key == "mine" || shelf.key.startsWith("group-")
            val kept = withoutRepeats(built.filterIsInstance<Shelf.Titles>() + listOfNotNull(shelf as? Shelf.Titles), { !personal }) {
                it.items.map { item -> item.id }
            }
            if (shelf is Shelf.Titles && kept.lastOrNull() != shelf) return
            built += shelf
        }
        if (movies) {
            add(
                titles(
                    "continue",
                    R.string.home_continue,
                    graph.continueCards(playlist, SHELF_LIMIT).filter { it.type == ContentType.MOVIE }.mapNotNull { card ->
                        graph.movie(playlist, card.id)?.let(::movie)
                    },
                ),
            )
            add(
                titles(
                    "trending",
                    R.string.home_trending_movies,
                    graph.moviesOfList(playlist, ExternalList.TRENDING_MOVIES, SHELF_LIMIT).map(::movie),
                ),
            )
            add(titles("recent", R.string.library_recently_added, graph.recentMovies(playlist, SHELF_LIMIT).map(::movie)))
            add(
                titles(
                    "popular",
                    R.string.home_popular_movies,
                    graph.moviesOfList(
                        playlist,
                        ExternalList.POPULAR_MOVIES,
                        SHELF_LIMIT,
                    ).ifEmpty { graph.popularMovies(playlist, SHELF_LIMIT) }
                        .map(::movie),
                ),
            )
            graph.becauseYouWatched(playlist, SHELF_LIMIT)?.let { pick ->
                add(Shelf.Titles("because", resources.getString(R.string.home_because, pick.seed.title), pick.movies.map(::movie)))
            }
            add(
                titles(
                    "top",
                    R.string.home_top_movies,
                    graph.moviesOfList(
                        playlist,
                        ExternalList.TOP_MOVIES,
                        SHELF_LIMIT,
                    ).ifEmpty { graph.topRatedMovies(playlist, SHELF_LIMIT) }
                        .map(::movie),
                ),
            )
            add(titles("mine", R.string.home_my_list, graph.favoriteMovies(playlist, SHELF_LIMIT).map(::movie)))
            for (group in graph.userGroups(playlist).filter { it.movieCount > 0 }) {
                add(Shelf.Titles("group-${group.id}", group.title, graph.moviesInUserGroup(playlist, group.id, SHELF_LIMIT).map(::movie)))
            }
        } else {
            add(
                titles(
                    "trending",
                    R.string.home_trending_series,
                    graph.seriesOfList(playlist, ExternalList.TRENDING_SERIES, SHELF_LIMIT).map(::series),
                ),
            )
            add(titles("new", R.string.home_new_episodes, graph.newEpisodeSeries(playlist, SHELF_LIMIT).map(::series)))
            add(
                titles(
                    "popular",
                    R.string.home_popular_series,
                    graph.seriesOfList(
                        playlist,
                        ExternalList.POPULAR_SERIES,
                        SHELF_LIMIT,
                    ).ifEmpty { graph.popularSeries(playlist, SHELF_LIMIT) }
                        .map(::series),
                ),
            )
            add(
                titles(
                    "top",
                    R.string.home_top_series,
                    graph.seriesOfList(
                        playlist,
                        ExternalList.TOP_SERIES,
                        SHELF_LIMIT,
                    ).ifEmpty { graph.topRatedSeries(playlist, SHELF_LIMIT) }
                        .map(::series),
                ),
            )
            add(titles("mine", R.string.home_my_list, graph.favoriteSeries(playlist, SHELF_LIMIT).map(::series)))
            for (group in graph.userGroups(playlist).filter { it.seriesCount > 0 }) {
                add(Shelf.Titles("group-${group.id}", group.title, graph.seriesInUserGroup(playlist, group.id, SHELF_LIMIT).map(::series)))
            }
        }
        // Publish what is ready before the genre shelves, which take longer on a large library.
        shelves = built.toList()

        val genres = if (movies) graph.movieGenres(playlist, GENRE_TILES) else graph.seriesGenres(playlist, GENRE_TILES)
        for ((genre, count) in genres.take(GENRE_SHELVES)) {
            if (count < MIN_GENRE_TITLES) continue
            val items = if (movies) {
                graph.moviesOfGenre(playlist, genre, SHELF_LIMIT).map(::movie)
            } else {
                graph.seriesOfGenre(playlist, genre, SHELF_LIMIT).map(::series)
            }
            add(Shelf.Titles("genre-$genre", genre, items))
        }
        add(
            Shelf.Tiles(
                "genres",
                resources.getString(R.string.library_by_genre),
                genres.map { (name, count) -> BrowseTile(Browse.genre(name), name, count) },
            ),
        )
        if (movies) {
            val decades = graph.movieDecades(playlist).filter { (decade, _) -> decade in DECADES }
            add(
                Shelf.Tiles(
                    "decades",
                    resources.getString(R.string.library_by_decade),
                    decades.map { (decade, count) ->
                        BrowseTile(
                            Browse.decade(decade),
                            resources.getString(R.string.library_decade, decade),
                            count,
                        )
                    },
                ),
            )
        }
        val categories = graph.libraryGroups(playlist, unit)
        add(
            Shelf.Tiles(
                "categories",
                resources.getString(R.string.library_all_categories),
                listOf(
                    BrowseTile(
                        Browse.ALL,
                        resources.getString(if (movies) R.string.library_all_movies else R.string.library_all_series),
                        null,
                    ),
                ) +
                    categories.map { BrowseTile(Browse.category(it.id), it.title, it.itemCount) },
            ),
        )
        shelves = built.toList()
    }

    var menuFor by remember { mutableStateOf<Pair<String, ShelfItem>?>(null) }
    val coroutines = rememberCoroutineScope()
    val loaded = shelves ?: return LuzSkeletonShelf(CardShape.POSTER, Modifier.padding(top = Tokens.space16))
    val resolver = rememberArtworkResolver(playlist)
    val featured = remember(loaded) {
        loaded.filterIsInstance<Shelf.Titles>().filter { it.key != "continue" }.flatMap { it.items }.firstOrNull { it.backdrop != null }
    }
    // Where the remote lands when the section opens: the hero's Play, or the first title when nothing has a backdrop.
    val firstKey = if (featured != null) {
        LibraryTags.HERO_PLAY
    } else {
        when (val first = loaded.firstOrNull()) {
            is Shelf.Titles -> LibraryTags.shelfItem(first.key, first.items.first().id)
            is Shelf.Tiles -> LibraryTags.tile(first.tiles.first().key)
            null -> null
        }
    }
    LaunchedEffect(firstKey) { onFirstKey(firstKey) }
    // Back from a grid: bring the shelf holding its tile into view — it is near the bottom, beyond what the list has
    // composed — and put the remote on the tile.
    val listState = rememberLazyListState()
    val returnIndex = returnTo?.let { key -> loaded.indexOfFirst { it is Shelf.Tiles && it.tiles.any { tile -> tile.key == key } } }
    LaunchedEffect(returnTo, returnIndex) {
        if (returnTo == null || returnIndex == null || returnIndex < 0) return@LaunchedEffect
        listState.scrollToItem(returnIndex + 1)
        repeat(RETURN_ATTEMPTS) {
            if (focus.requestFocus(LibraryTags.tile(returnTo))) {
                onReturned()
                return@LaunchedEffect
            }
            delay(RETURN_INTERVAL_MS)
        }
        onReturned()
    }
    val ambient = rememberAmbientColor(featured?.backdrop, resolver)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to ambient, LIBRARY_HERO_FRACTION to ambient, 1f to Tokens.bgBase)),
    ) {
        CalmScrolling {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(Tokens.shelfSpacing),
            ) {
                if (featured != null) {
                    item(key = "hero") {
                        LuzHero(
                            title = featured.title,
                            meta =
                            listOf(
                                stringResource(if (movies) R.string.home_kind_movie else R.string.home_kind_series),
                            ) + featured.facts,
                            detail = featured.plot,
                            titleArt = {
                                val type = if (movies) ContentType.MOVIE else ContentType.SERIES
                                val art = rememberTitlePageArt(type, featured.title, featured.year, null, emptyList(), ask = false)
                                TitleLogo(art.logoUrl, featured.title)
                            },
                            modifier = Modifier.fillParentMaxHeight(LIBRARY_HERO_FRACTION).revealsListTop(listState),
                            room = ambient,
                            artworkOf = featured.backdrop,
                            actions = {
                                LuzButton(
                                    stringResource(if (movies) R.string.home_hero_play else R.string.home_hero_open),
                                    { if (movies) onPlay(featured.id) else onOpen(featured.id) },
                                    Modifier.rememberedFocus(focus, LibraryTags.HERO_PLAY),
                                    kind = ButtonKind.PRIMARY,
                                    icon = LuzIcons.Play,
                                )
                                LuzIconButton(
                                    LuzIcons.Info,
                                    stringResource(R.string.home_hero_info),
                                    { onOpen(featured.id) },
                                    Modifier.rememberedFocus(focus, LibraryTags.HERO_INFO),
                                )
                            },
                        ) { art, modifier -> ArtworkImage(art, resolver, null, modifier, BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX) }
                    }
                } else {
                    item(key = "title") {
                        Text(
                            stringResource(if (movies) R.string.section_movies else R.string.section_series),
                            style = MaterialTheme.typography.displaySmall,
                            color = Tokens.textPrimary,
                            modifier = Modifier.padding(start = Tokens.contentStart, top = Tokens.space10),
                        )
                    }
                }
                items(loaded.size, key = { loaded[it].key }) { index ->
                    when (val shelf = loaded[index]) {
                        is Shelf.Titles -> LuzShelf(shelf.title) {
                            items(shelf.items.size, key = { shelf.items[it].id }) { i ->
                                val item = shelf.items[i]
                                LuzCard(
                                    title = item.title,
                                    subtitle = item.caption,
                                    shape = CardShape.POSTER,
                                    progress = item.progress,
                                    modifier = Modifier.rememberedFocus(focus, LibraryTags.shelfItem(shelf.key, item.id)),
                                    onLongClick = { menuFor = shelf.key to item },
                                    onClick = { onOpen(item.id) },
                                ) { art -> ArtworkImage(item.poster, resolver, item.title, art) }
                            }
                        }
                        is Shelf.Tiles -> LuzShelf(shelf.title) {
                            items(shelf.tiles.size, key = { shelf.tiles[it].key }) { i ->
                                val tile = shelf.tiles[i]
                                Tile(tile, Modifier.rememberedFocus(focus, LibraryTags.tile(tile.key))) { onBrowse(tile.key) }
                            }
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(Tokens.space10)) }
            }
        }
    }
    menuFor?.let { (shelfKey, item) ->
        TitleMenu(
            playlist,
            TitleTarget(if (movies) ContentType.MOVIE else ContentType.SERIES, item.id, item.title, item.favorite),
            onOpen = { onOpen(item.id) },
            onDismiss = {
                menuFor = null
                coroutines.launch {
                    repeat(RETURN_ATTEMPTS) {
                        if (focus.requestFocus(LibraryTags.shelfItem(shelfKey, item.id))) return@launch
                        delay(RETURN_INTERVAL_MS)
                    }
                }
            },
        )
    }
}

/** A genre, decade or category: its name on a quiet glass tile, and how many titles it holds. */
@Composable
private fun Tile(tile: BrowseTile, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Tokens.radiusMedium)
    Column(
        modifier = modifier
            .size(TILE_WIDTH, TILE_HEIGHT)
            .luzLift(focused, shape, shadow = false)
            .clip(shape)
            .background(if (focused) Tokens.raisedFocused else Tokens.raised)
            .then(if (focused) Modifier.border(Tokens.focusRingWidth, Tokens.hairline, shape) else Modifier)
            .luzClickable(onClick = onClick, onFocus = { focused = it })
            .padding(Tokens.space4),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            tile.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) Tokens.textPrimary else Tokens.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        tile.count?.let {
            Text(
                pluralStringResource(R.plurals.library_titles, it.toInt(), it.toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = Tokens.textTertiary,
            )
        }
    }
}

private const val LIBRARY_HERO_FRACTION = 0.62f
private const val RETURN_ATTEMPTS = 40
private const val RETURN_INTERVAL_MS = 50L
private const val SHELF_LIMIT = 20
private const val GENRE_SHELVES = 6
private const val GENRE_TILES = 40
private const val MIN_GENRE_TITLES = 8

/** Rebuilt for background film pages once every this many progress steps (each step is 50 pages). */
private const val SHELF_REFRESH_STEP = 10
private val DECADES = 1900..2090
private val TILE_WIDTH = 180.dp
private val TILE_HEIGHT = 96.dp
