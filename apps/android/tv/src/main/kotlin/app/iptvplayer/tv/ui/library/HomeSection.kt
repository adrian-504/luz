package app.iptvplayer.tv.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.app.ContinueCard
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
import app.iptvplayer.tv.ui.theme.revealsListTop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Every row Home can show, in its default order — the list Settings offers the viewer to choose from. */
val HOME_ROW_TITLES: List<Pair<String, Int>> = listOf(
    HomeTags.CONTINUE to R.string.home_continue,
    HomeTags.CHANNELS to R.string.home_your_channels,
    HomeTags.LIVE to R.string.home_live_now,
    HomeTags.COMING_UP to R.string.home_coming_up,
    HomeTags.TRENDING_MOVIES to R.string.home_trending_movies,
    HomeTags.POPULAR_MOVIES to R.string.home_popular_movies,
    HomeTags.SERIES to R.string.home_new_episodes,
    HomeTags.MOVIES to R.string.home_recent_movies,
    HomeTags.BECAUSE to R.string.home_because_setting,
    HomeTags.GENRE to R.string.home_genre_setting,
    HomeTags.TOP_MOVIES to R.string.home_top_movies,
    HomeTags.TRENDING_SERIES to R.string.home_trending_series,
    HomeTags.POPULAR_SERIES to R.string.home_popular_series,
    HomeTags.TOP_SERIES to R.string.home_top_series,
    HomeTags.MY_LIST to R.string.home_my_list,
)

object HomeTags {
    fun item(row: String, id: String) = "home-$row-$id"

    const val CONTINUE = "continue"
    const val CHANNELS = "channels"
    const val LIVE = "live"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val POPULAR_MOVIES = "popular-movies"
    const val TRENDING_MOVIES = "trending-movies"
    const val TRENDING_SERIES = "trending-series"
    const val TOP_MOVIES = "top-movies"
    const val POPULAR_SERIES = "popular-series"
    const val TOP_SERIES = "top-series"
    const val BECAUSE = "because"
    const val MY_LIST = "my-list"
    const val FAVOURITE_CHANNELS = "favourite-channels"
    const val RECENT = "recent"
    const val COMING_UP = "coming-up"
    const val GENRE = "genre"
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
    val year: Int? = null,
    /** For a channel's card: the name, drawn as a monogram when the logo is missing. */
    val channelName: String? = null,
    /** Plays it (a film, an episode to resume) or, for a series, opens it. */
    val open: () -> Unit,
    /** Opens its detail page, where there is one. */
    val details: (() -> Unit)? = null,
    /** On "Continue watching": what a long press can mark as watched or take off the row. */
    val continuing: ContinueCard? = null,
)

private data class HomeRow(val id: String, val title: String, val cards: List<HomeCard>, val landscape: Boolean = false)

/**
 * What a Home row is made of, before it is loaded. The rows are described rather than written out one after another so
 * that the order — and the viewer's own choice of which rows to show (PRODUCT_DIRECTIVE.md §3) — is data.
 */
private data class HomeRowSpec(
    val id: String,
    val title: Int,
    val landscape: Boolean = false,
    /** A title that depends on what was loaded ("Because you watched Heat"); null keeps [title]. */
    val titleOf: (() -> String?)? = null,
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
    /** My Luz (ADR-0044): the same screen, holding only what the viewer has made their own. */
    myLuz: Boolean = false,
    placeholder: @Composable () -> Unit,
) {
    val graph = LocalAppGraph.current
    val resources = LocalResources.current
    val revision by graph.revision.collectAsState()
    val watched by graph.watchRevision.collectAsState()
    // Film pages arriving in the background fill the shelves built from them, but Home is only rebuilt every so often
    // for it: the rows do not shuffle under the viewer each time a page arrives.
    val details by graph.detailRevision.collectAsState()
    val detailsStep = details / DETAIL_REFRESH_STEP
    val lists by graph.listRevision.collectAsState()
    var playlist by remember { mutableStateOf<PlaylistId?>(null) }
    var rows by remember { mutableStateOf<List<HomeRow>?>(null) }
    val movieKind = stringResource(R.string.home_kind_movie)
    val seriesKind = stringResource(R.string.home_kind_series)

    LaunchedEffect(revision, watched, detailsStep, lists) {
        val source = graph.currentSource()
        playlist = source?.playlistId
        val id = source?.playlistId
        fun movieCard(p: PlaylistId, movie: MovieRow) = HomeCard(
            key = movie.id,
            title = movie.title,
            caption = movie.year?.toString(),
            poster = movie.poster,
            fraction = movie.progress?.takeIf { !it.completed }?.fraction,
            backdrop = movie.backdrop,
            plot = movie.plot,
            type = ContentType.MOVIE,
            facts = listOfNotNull(movieKind, movie.genres.firstOrNull(), movie.year?.toString(), movie.rating),
            favorite = movie.isFavorite,
            year = movie.year,
            open = { onPlayContent(p, ContentType.MOVIE, movie.id) },
            details = { onOpenMovie(p, movie.id) },
        )
        fun seriesCard(p: PlaylistId, series: SeriesRow) = HomeCard(
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
            year = series.year,
            open = { onOpenSeries(p, series.id) },
            details = { onOpenSeries(p, series.id) },
        )
        var because: String? = null
        var genreTitle: String? = null
        val now = Clock.System.now()
        val specs = listOf(
            HomeRowSpec(HomeTags.CONTINUE, R.string.home_continue, landscape = true) { p ->
                graph.continueCards(p, ROW_LIMIT).map { card ->
                    HomeCard(
                        // The film or the series, not the file: the card stays where it is as a series moves on.
                        key = card.pageId,
                        title = card.title,
                        caption = listOfNotNull(
                            card.subtitle,
                            if (card.next) {
                                resources.getString(
                                    R.string.home_next_episode,
                                )
                            } else {
                                card.remaining?.let { timeLeft(resources, it) }
                            },
                        ).joinToString(" · ").ifEmpty { null },
                        poster = card.poster,
                        backdrop = card.backdrop,
                        fraction = card.fraction,
                        type = card.type,
                        facts = listOfNotNull(card.subtitle),
                        favorite = card.favorite,
                        // Until the episodes are read again after a refresh, the series' page is the way in.
                        open = { if (card.needsEpisodes) onOpenSeries(p, card.pageId) else onPlayContent(p, card.type, card.id) },
                        details = { if (card.type == ContentType.MOVIE) onOpenMovie(p, card.pageId) else onOpenSeries(p, card.pageId) },
                        continuing = card,
                    )
                }
            },
            // The channels the viewer actually watches — favourites first, then the ones they return to most — with what is
            // on them now. A viewer who has watched nothing and kept no favourites gets "Live now" instead.
            HomeRowSpec(HomeTags.CHANNELS, R.string.home_your_channels, landscape = true) { p ->
                channelCards(graph, p, mine = true, onPlay = onPlayChannel)
            },
            HomeRowSpec(HomeTags.LIVE, R.string.home_live_now, landscape = true) { p ->
                channelCards(graph, p, mine = false, onPlay = onPlayChannel)
            },
            // What starts soon (or just started) on the viewer's own channels, from the stored guide; OK jumps to it.
            HomeRowSpec(HomeTags.COMING_UP, R.string.home_coming_up, landscape = true) { p ->
                graph.comingUp(p, ROW_LIMIT, now).map { item ->
                    HomeCard(
                        key = "${item.channel.id.value}-${item.programme.start.toEpochMilliseconds()}",
                        title = item.programme.title,
                        caption = "${startsText(resources, item.programme.start, now)} · ${item.channel.label}",
                        poster = item.channel.logo,
                        fraction = null,
                        channelName = item.channel.label,
                        open = {
                            onPlayChannel(p, if (item.channel.isFavorite) ChannelScope.Favorites else ChannelScope.All, item.channel.id)
                        },
                    )
                }
            },
            // Trending comes from TMDB when the viewer gave a key (ADR-0038); without one the row is empty and left out.
            HomeRowSpec(HomeTags.TRENDING_MOVIES, R.string.home_trending_movies) { p ->
                graph.moviesOfList(p, ExternalList.TRENDING_MOVIES, ROW_LIMIT).map { movieCard(p, it) }
            },
            // Popular and highest rated follow TMDB's lists when there are any, and the provider's ratings otherwise.
            HomeRowSpec(HomeTags.POPULAR_MOVIES, R.string.home_popular_movies) { p ->
                graph.moviesOfList(p, ExternalList.POPULAR_MOVIES, ROW_LIMIT).ifEmpty { graph.popularMovies(p, ROW_LIMIT) }
                    .map { movieCard(p, it) }
            },
            HomeRowSpec(HomeTags.SERIES, R.string.home_new_episodes) { p ->
                graph.newEpisodeSeries(p, ROW_LIMIT).map { seriesCard(p, it) }
            },
            HomeRowSpec(HomeTags.MOVIES, R.string.home_recent_movies) { p ->
                graph.recentMovies(p, ROW_LIMIT).map { movieCard(p, it) }
            },
            HomeRowSpec(HomeTags.BECAUSE, R.string.home_because_setting, titleOf = { because }) { p ->
                graph.becauseYouWatched(p, ROW_LIMIT)?.let { pick ->
                    because = resources.getString(R.string.home_because, pick.seed.title)
                    pick.movies.map { movieCard(p, it) }
                }.orEmpty()
            },
            HomeRowSpec(HomeTags.GENRE, R.string.home_genre_setting, titleOf = { genreTitle }) { p ->
                graph.genrePick(p, ROW_LIMIT)?.let { pick ->
                    genreTitle = resources.getString(R.string.home_genre, pick.genre)
                    pick.movies.map { movieCard(p, it) }
                }.orEmpty()
            },
            HomeRowSpec(HomeTags.TOP_MOVIES, R.string.home_top_movies) { p ->
                graph.moviesOfList(p, ExternalList.TOP_MOVIES, ROW_LIMIT).ifEmpty { graph.topRatedMovies(p, ROW_LIMIT) }
                    .map { movieCard(p, it) }
            },
            HomeRowSpec(HomeTags.TRENDING_SERIES, R.string.home_trending_series) { p ->
                graph.seriesOfList(p, ExternalList.TRENDING_SERIES, ROW_LIMIT).map { seriesCard(p, it) }
            },
            HomeRowSpec(HomeTags.POPULAR_SERIES, R.string.home_popular_series) { p ->
                graph.seriesOfList(p, ExternalList.POPULAR_SERIES, ROW_LIMIT).ifEmpty { graph.popularSeries(p, ROW_LIMIT) }
                    .map { seriesCard(p, it) }
            },
            HomeRowSpec(HomeTags.TOP_SERIES, R.string.home_top_series) { p ->
                graph.seriesOfList(p, ExternalList.TOP_SERIES, ROW_LIMIT).ifEmpty { graph.topRatedSeries(p, ROW_LIMIT) }
                    .map { seriesCard(p, it) }
            },
            // My List: the films and shows the viewer kept, newest first.
            HomeRowSpec(HomeTags.MY_LIST, R.string.home_my_list) { p ->
                graph.favoriteMovies(p, ROW_LIMIT).map { movieCard(p, it) } + graph.favoriteSeries(p, ROW_LIMIT).map { seriesCard(p, it) }
            },
        )
        val chosen = graph.homeRows()?.let { withNewRows(it, graph.homeRowsKnown()) }
        // My Luz: what is in progress, what was kept, the channels kept, each group the viewer made, what was watched.
        val myLuzSpecs = if (myLuz && id != null) {
            val groups = graph.userGroups(id).filter { it.movieCount + it.seriesCount > 0 }
            listOfNotNull(specs.firstOrNull { it.id == HomeTags.CONTINUE }) + listOf(
                HomeRowSpec(HomeTags.MY_LIST, R.string.home_my_list) { p ->
                    graph.favoriteMovies(p, MY_LIST_LIMIT).map { movieCard(p, it) } +
                        graph.favoriteSeries(p, MY_LIST_LIMIT).map { seriesCard(p, it) }
                },
                HomeRowSpec(HomeTags.FAVOURITE_CHANNELS, R.string.home_favourite_channels, landscape = true) { p ->
                    channelCards(graph, p, mine = true, onPlay = onPlayChannel, favouritesOnly = true)
                },
            ) + groups.map { group ->
                HomeRowSpec("group-${group.id}", R.string.home_favourite_channels, titleOf = { group.title }) { p ->
                    graph.moviesInUserGroup(p, group.id, MY_LIST_LIMIT).map { movieCard(p, it) } +
                        graph.seriesInUserGroup(p, group.id, MY_LIST_LIMIT).map { seriesCard(p, it) }
                }
            } + HomeRowSpec(HomeTags.RECENT, R.string.home_recently_watched) { p ->
                graph.recentlyWatchedMovies(p, ROW_LIMIT).map { movieCard(p, it) }
            }
        } else {
            null
        }
        rows = if (id == null) {
            emptyList()
        } else {
            fun arranged(loaded: List<HomeRow>): List<HomeRow> {
                val kept =
                    withoutRepeats(
                        loaded.filter { it.cards.isNotEmpty() },
                        { it.id !in PERSONAL_ROWS },
                    ) { it.cards.map { card -> card.key } }
                // "Live now" is there for a viewer with none of their own channels yet; once they have some, that row says it better.
                return if (kept.any { it.id == HomeTags.CHANNELS }) kept.filterNot { it.id == HomeTags.LIVE } else kept
            }
            // The viewer's own choice of rows and their order, when they have made one; otherwise Home's own order.
            val wanted = myLuzSpecs ?: chosen?.mapNotNull { key -> specs.firstOrNull { it.id == key } } ?: specs
            // The first time Home opens, each row appears as soon as it is read, rather than all of them after the slowest.
            // Later reloads replace the rows in one step, so nothing on screen shrinks and grows again under the viewer.
            val firstLoad = rows == null
            val loaded = mutableListOf<HomeRow>()
            for (spec in wanted) {
                val cards = spec.load(id)
                loaded += HomeRow(spec.id, spec.titleOf?.invoke() ?: resources.getString(spec.title), cards, spec.landscape)
                if (firstLoad) {
                    arranged(loaded).takeIf { it.isNotEmpty() }?.let {
                        rows = it
                        onFirstKey(firstCardKey(it))
                    }
                }
            }
            arranged(loaded)
        }
        onFirstKey(rows?.takeIf { it.isNotEmpty() }?.let(::firstCardKey))
    }

    val shown = rows ?: return
    if (shown.isEmpty()) {
        placeholder()
        return
    }
    val resolver = rememberArtworkResolver(playlist)
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<Pair<String, HomeCard>?>(null) }
    val listState = rememberLazyListState()
    val greeting = greeting(resources, java.time.LocalTime.now().hour)
    fun stageOf(row: HomeRow, card: HomeCard) = StageInfo(
        eyebrow = if (row.id == shown.first().id && !myLuz) "$greeting · ${row.title}" else row.title,
        title = card.title,
        facts = card.facts.ifEmpty { listOfNotNull(card.caption) },
        synopsis = card.plot,
        art = card.backdrop.takeIf { card.channelName == null },
        poster = card.poster,
        progress = card.fraction,
        logoOf = card.type?.takeIf { it == ContentType.MOVIE || it == ContentType.SERIES },
        year = card.year,
        isChannel = card.channelName != null,
    )
    // What the stage says about the card the remote is on; written from focus and read only by the stage (ADR-0043).
    val onCard = remember { mutableStateOf<StageInfo?>(null) }
    LaunchedEffect(shown) {
        if (onCard.value == null) shown.first().let { row -> row.cards.firstOrNull()?.let { onCard.value = stageOf(row, it) } }
    }

    Column(Modifier.fillMaxSize().background(Tokens.bgBase)) {
        LuzStage({ onCard.value }, resolver, Modifier.fillMaxWidth().weight(STAGE_FRACTION))
        StageShelves(Modifier.fillMaxWidth().weight(1f - STAGE_FRACTION)) {
            CalmScrolling {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(Tokens.shelfSpacing),
                ) {
                    items(shown.size, key = { shown[it].id }) { index ->
                        val row = shown[index]
                        LuzShelf(title = row.title) {
                            items(row.cards.size, key = { row.cards[it].key }) { cardIndex ->
                                val card = row.cards[cardIndex]
                                val channel = card.channelName != null
                                LuzCard(
                                    title = card.title,
                                    subtitle = card.caption,
                                    shape = if (row.landscape) CardShape.LANDSCAPE else CardShape.POSTER,
                                    progress = card.fraction,
                                    modifier = Modifier
                                        .rememberedFocus(focus, HomeTags.item(row.id, card.key))
                                        .onFocusChanged { if (it.isFocused) onCard.value = stageOf(row, card) },
                                    onLongClick = if (hasMenu(card)) ({ menuFor = row.id to card }) else null,
                                    onClick = card.open,
                                ) { modifier ->
                                    if (channel) {
                                        ArtworkImage(card.poster, resolver, null, modifier, fit = true, name = card.channelName)
                                    } else {
                                        // A wide card shows the wide picture: a film's backdrop in Continue watching, not its
                                        // poster cropped to a strip.
                                        val art = if (row.landscape) card.backdrop ?: card.poster else card.poster
                                        ArtworkImage(art, resolver, card.title, modifier, LANDSCAPE_PX_WIDTH, LANDSCAPE_PX_HEIGHT)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "end") { Spacer(Modifier.height(Tokens.space16)) }
                }
            }
        }
    }
    val menuPlaylist = playlist
    val menu = menuFor
    if (menu != null && menuPlaylist != null) {
        val (rowId, card) = menu
        // Back to the card — or, when it has just left the row, to the one now first in it.
        val returnToCard = {
            menuFor = null
            scope.launch {
                repeat(MENU_RETURN_ATTEMPTS) {
                    if (focus.requestFocus(HomeTags.item(rowId, card.key))) return@launch
                    val first = rows?.firstOrNull { it.id == rowId }?.cards?.firstOrNull()
                    if (first != null && focus.requestFocus(HomeTags.item(rowId, first.key))) return@launch
                    delay(MENU_RETURN_INTERVAL_MS)
                }
            }
            Unit
        }
        val continuing = card.continuing
        val type = card.type
        if (continuing != null) {
            ContinueMenu(menuPlaylist, continuing, onOpen = { card.details?.invoke() }, onDismiss = returnToCard)
        } else if (type != null) {
            TitleMenu(
                menuPlaylist,
                TitleTarget(type, card.key, card.title, card.favorite),
                onOpen = { (card.details ?: card.open)() },
                onDismiss = returnToCard,
            )
        }
    }
}

/**
 * The viewer's saved rows with any row Luz gained since they saved them, each placed after the row it follows by
 * default: a new row is on until they turn it off. [known] is every row there was when they saved.
 */
fun withNewRows(saved: List<String>, known: List<String>?): List<String> {
    val all = HOME_ROW_TITLES.map { it.first }
    val seen = (known ?: all - NEW_ROWS_2026_09).toSet()
    val result = saved.toMutableList()
    all.filter { it !in seen && it !in result }.forEach { row ->
        val before = all.subList(0, all.indexOf(row)).lastOrNull { it in result }
        result.add(before?.let { result.indexOf(it) + 1 } ?: 0, row)
    }
    return result
}

/** Rows added on 2026-09-18, before which Luz did not note which rows existed when the viewer saved theirs. */
private val NEW_ROWS_2026_09 = setOf(HomeTags.COMING_UP, HomeTags.GENRE)

/** "Good morning" until noon, "Good afternoon" until six, then "Good evening" through the night. */
internal fun greeting(resources: android.content.res.Resources, hour: Int): String = resources.getString(
    when (hour) {
        in 5..11 -> R.string.home_good_morning
        in 12..17 -> R.string.home_good_afternoon
        else -> R.string.home_good_evening
    },
)

/** "32 min left", "1 h 5 min left". */
internal fun timeLeft(resources: android.content.res.Resources, left: kotlin.time.Duration): String {
    val minutes = left.inWholeMinutes.coerceAtLeast(1)
    return if (minutes < 60) {
        resources.getString(R.string.home_minutes_left, minutes)
    } else {
        resources.getString(R.string.home_hours_left, minutes / 60, minutes % 60)
    }
}

/** "Started 5 min ago", "In 20 min", "At 21:00". */
private fun startsText(resources: android.content.res.Resources, start: kotlin.time.Instant, now: kotlin.time.Instant): String {
    val minutes = (start - now).inWholeMinutes
    return when {
        minutes <= 0 -> resources.getString(R.string.home_started_ago, -minutes)
        minutes < 60 -> resources.getString(R.string.home_starts_in, minutes)
        else -> resources.getString(
            R.string.home_starts_at,
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(
                java.time.Instant.ofEpochMilli(start.toEpochMilliseconds()).atZone(java.time.ZoneId.systemDefault()),
            ),
        )
    }
}

private const val MENU_RETURN_ATTEMPTS = 10
private const val MENU_RETURN_INTERVAL_MS = 50L
private const val LANDSCAPE_PX_WIDTH = 480
private const val LANDSCAPE_PX_HEIGHT = 270

/** Whether a long press on the card opens a menu: films and series do (and Continue watching), channels do not. */
private fun hasMenu(card: HomeCard): Boolean = card.continuing != null || card.type != null

/** Where the remote lands when Home opens: the first card of the first row. */
private fun firstCardKey(rows: List<HomeRow>): String? =
    rows.firstOrNull()?.let { row -> row.cards.firstOrNull()?.let { HomeTags.item(row.id, it.key) } }

/**
 * Channel cards with what is on now underneath: the viewer's own channels ([mine] — favourites, then the most watched),
 * or the first channels of the list.
 */
private suspend fun channelCards(
    graph: AppGraph,
    playlist: PlaylistId,
    mine: Boolean,
    onPlay: (PlaylistId, ChannelScope, ChannelId) -> Unit,
    favouritesOnly: Boolean = false,
): List<HomeCard> {
    val channels = if (favouritesOnly) {
        graph.favoriteChannels(playlist).take(ROW_LIMIT)
    } else if (mine) {
        (graph.favoriteChannels(playlist) + graph.mostWatchedChannels(playlist, ROW_LIMIT)).distinctBy { it.id }.take(ROW_LIMIT)
    } else {
        graph.channels(playlist, null).take(ROW_LIMIT)
    }
    if (channels.isEmpty()) return emptyList()
    val guide = graph.storedNowNext(playlist, channels, Clock.System.now())
    return channels.map { channel ->
        HomeCard(
            key = channel.id.value,
            title = channel.label,
            caption = guide[channel.id.value]?.current?.title,
            poster = channel.logo,
            fraction = null,
            channelName = channel.label,
            // Zapping from a favourite stays among favourites; from any other channel, the whole list.
            open = { onPlay(playlist, if (channel.isFavorite) ChannelScope.Favorites else ChannelScope.All, channel.id) },
        )
    }
}

/**
 * Leaves out a row that mostly repeats one above it. With a provider's ratings, "popular" and "highest rated" can come
 * out as nearly the same titles, and two identical shelves one after the other look broken rather than generous.
 */
internal fun <T> withoutRepeats(rows: List<T>, checked: (T) -> Boolean, idsOf: (T) -> List<String>): List<T> {
    val kept = mutableListOf<T>()
    for (row in rows) {
        val ids = idsOf(row)
        // The viewer's own rows — what they are watching, what they kept — always stay, whatever else shows the same titles.
        val repeats = checked(row) && ids.isNotEmpty() && kept.any { earlier ->
            val shown = idsOf(earlier).toSet()
            ids.count { it in shown } >= ids.size * REPEAT_SHARE
        }
        if (!repeats) kept += row
    }
    return kept
}

private const val REPEAT_SHARE = 0.6
private val PERSONAL_ROWS = setOf(
    HomeTags.CONTINUE,
    HomeTags.CHANNELS,
    HomeTags.LIVE,
    HomeTags.MY_LIST,
    HomeTags.FAVOURITE_CHANNELS,
    HomeTags.RECENT,
)
private const val ROW_LIMIT = 20

/** My Luz shows all of what was kept, up to this many of each; Home's shelves stay short. */
private const val MY_LIST_LIMIT = 100

/** Home is rebuilt for background film pages once every this many progress steps (each step is 50 pages). */
private const val DETAIL_REFRESH_STEP = 10
