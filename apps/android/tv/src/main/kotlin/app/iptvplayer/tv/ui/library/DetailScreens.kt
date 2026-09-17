package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.NextEpisode
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.MovieVersionRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.storage.TitleDetailRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.player.clock
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.ButtonKind
import app.iptvplayer.tv.ui.theme.CalmScrolling
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzButton
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzEmptyState
import app.iptvplayer.tv.ui.theme.LuzHero
import app.iptvplayer.tv.ui.theme.LuzIconButton
import app.iptvplayer.tv.ui.theme.LuzIcons
import app.iptvplayer.tv.ui.theme.LuzMenu
import app.iptvplayer.tv.ui.theme.LuzMenuItem
import app.iptvplayer.tv.ui.theme.LuzShelf
import app.iptvplayer.tv.ui.theme.LuzStatusLine
import app.iptvplayer.tv.ui.theme.Tokens
import app.iptvplayer.tv.ui.theme.luzClickable
import kotlinx.coroutines.launch
import kotlin.time.Duration

object DetailTags {
    const val PLAY = "detail-play"
    const val PLAY_FROM_START = "detail-play-from-start"
    const val FAVORITE = "detail-favorite"
    const val EPISODES_STATUS = "detail-episodes-status"
    const val TRAILER = "detail-trailer"
    const val ABOUT = "detail-about"

    fun person(name: String, directed: Boolean) = "detail-person-${if (directed) "d" else "c"}-$name"

    fun season(number: Int) = "detail-season-$number"

    fun episode(id: String) = "detail-episode-$id"
}

/**
 * A film (FR-VOD-001), as a page of its own (DESIGN_SYSTEM.md §10): its picture across most of the screen, the title, the
 * facts and badges over its lower left, and what can be done — Play or Resume, start again, the trailer, keep it —
 * directly beneath. Below: the people in it, and everything else the provider sent about it (ADR-0035).
 *
 * The provider's page for the film is fetched the first time it is opened, unless the background fetch already has it.
 * When the provider lists the film in several versions (4K, HD, another language), Play asks which.
 */
@Composable
fun MovieDetailScreen(
    playlistId: PlaylistId,
    movieId: String,
    onPlay: (id: String, fromStart: Boolean) -> Unit,
    onPerson: (String) -> Unit,
) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    val watched by graph.watchRevision.collectAsState()
    val focus = rememberFocusMemory()
    var movie by remember { mutableStateOf<MovieRow?>(null) }
    var detail by remember { mutableStateOf<TitleDetailRow?>(null) }
    var versions by remember { mutableStateOf<List<MovieVersionRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<Boolean?>(null) }
    var trailerMissing by remember { mutableStateOf(false) }
    LaunchedEffect(revision, watched) {
        movie = graph.movie(playlistId, movieId)
        detail = graph.detail(playlistId, ContentType.MOVIE, movieId)
        loaded = true
        if (movie != null) {
            versions = graph.movieVersions(playlistId, movieId)
            if (detail == null && graph.loadMovieDetail(playlistId, movieId)) {
                detail = graph.detail(playlistId, ContentType.MOVIE, movieId)
            }
        }
    }
    val resolver = rememberArtworkResolver(playlistId)
    if (!loaded) return DetailRoom()
    val item = movie ?: return NotFound()
    val info = detail
    val resume = item.progress?.takeIf { !it.completed && it.position.isPositive() }
    val backdrop = info?.backdrop ?: item.backdrop ?: item.poster
    val ambient = rememberAmbientColor(backdrop, resolver)
    val genres = info?.genres?.takeIf { it.isNotEmpty() } ?: item.genres
    val duration = info?.duration ?: item.duration
    val rating = info?.rating ?: item.rating
    val credits = creditsOf(info?.directors.orEmpty(), info?.cast.orEmpty())
    val play = { fromStart: Boolean -> if (versions.size > 1) choosing = fromStart else onPlay(item.id, fromStart) }

    DetailRoom(ambient, MOVIE_HERO_FRACTION) {
        item(key = "hero") {
            LuzHero(
                title = item.title,
                meta = listOfNotNull(
                    (item.year ?: info?.year)?.toString(),
                    duration?.let { durationText(it) },
                    genres.take(2).joinToString(", ").ifEmpty { null },
                    info?.ageRating,
                    rating?.let { "\u2605 $it" },
                ),
                badges = badgesOf(item.quality, item.tags, item.language),
                detail = info?.plot ?: item.plot,
                detailLines = HERO_PLOT_LINES,
                modifier = Modifier.fillParentMaxHeight(MOVIE_HERO_FRACTION),
                room = ambient,
                artworkOf = backdrop,
                actions = {
                    LuzButton(
                        if (resume != null) {
                            stringResource(R.string.detail_resume, clock(resume.position.inWholeMilliseconds))
                        } else {
                            stringResource(R.string.detail_play)
                        },
                        { play(false) },
                        Modifier.rememberedFocus(focus, DetailTags.PLAY),
                        kind = ButtonKind.PRIMARY,
                        icon = LuzIcons.Play,
                    )
                    if (resume != null) {
                        LuzIconButton(
                            LuzIcons.Restart,
                            stringResource(R.string.detail_play_from_start),
                            { play(true) },
                            Modifier.rememberedFocus(focus, DetailTags.PLAY_FROM_START),
                        )
                    }
                    info?.trailer?.let { trailer ->
                        LuzIconButton(
                            LuzIcons.Trailer,
                            stringResource(if (trailerMissing) R.string.detail_trailer_unavailable else R.string.detail_trailer),
                            { trailerMissing = !openTrailer(context, trailer) },
                            Modifier.rememberedFocus(focus, DetailTags.TRAILER),
                        )
                    }
                    FavoriteButton(item.isFavorite, focus) {
                        coroutines.launch { graph.setFavorite(ContentType.MOVIE, item.id, !item.isFavorite) }
                    }
                },
            ) { art, modifier -> Backdrop(art, resolver, modifier) }
        }
        if (credits.isNotEmpty()) {
            item(key = "credits") { CreditsShelf(credits, focus, onPerson) }
        }
        item(key = "about") {
            AboutPanel(
                plot = info?.plot ?: item.plot,
                facts = factsOf(
                    genres, info?.releaseDate, item.year ?: info?.year, duration, info?.ageRating, rating, info?.country,
                    info?.directors.orEmpty(), info?.cast.orEmpty(),
                ),
                modifier = Modifier.rememberedFocus(focus, DetailTags.ABOUT),
            )
        }
    }
    choosing?.let { fromStart ->
        LuzMenu(
            title = stringResource(R.string.detail_choose_version),
            items = versions.map { version ->
                LuzMenuItem(
                    key = version.id,
                    label = badgesOf(version.quality, version.tags, version.language).joinToString(" · ").ifEmpty { version.title },
                ) { onPlay(version.id, fromStart) }
            },
            onDismiss = { choosing = null },
        )
    }
    RestoreFocusEffect(focus, DetailTags.PLAY)
}

/**
 * A series (FR-SER-001): the same picture and title as a film, Continue or Start for the episode that is next
 * (NextEpisode), and underneath, the seasons as quiet tabs over a shelf of that season's episodes. Xtream episodes load
 * from the provider the first time a series is opened.
 */
@Composable
fun SeriesDetailScreen(
    playlistId: PlaylistId,
    seriesId: String,
    onPlayEpisode: (episodeId: String, fromStart: Boolean) -> Unit,
    onPerson: (String) -> Unit,
) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    val watched by graph.watchRevision.collectAsState()
    val focus = rememberFocusMemory()
    var series by remember { mutableStateOf<SeriesRow?>(null) }
    var detail by remember { mutableStateOf<TitleDetailRow?>(null) }
    var episodes by remember { mutableStateOf<List<EpisodeRow>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var season by rememberSaveable { mutableStateOf<Int?>(null) }
    var lastWatchedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision, watched) {
        series = graph.seriesById(playlistId, seriesId)
        loaded = true
        if (series != null) {
            failed = !graph.loadSeriesDetail(playlistId, seriesId)
            lastWatchedId = graph.lastWatchedEpisodeId(seriesId)
            episodes = graph.episodes(playlistId, seriesId)
            detail = graph.detail(playlistId, ContentType.SERIES, seriesId)
        }
    }
    val resolver = rememberArtworkResolver(playlistId)
    if (!loaded) return DetailRoom()
    val item = series ?: return NotFound()
    val list = episodes
    val seasons = list.orEmpty().map { it.seasonNumber }.distinct()
    val shownSeason = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
    val next = list?.let { all ->
        NextEpisode.pick(all, all.firstOrNull { it.id == lastWatchedId }) { episode ->
            episode.progress?.let { if (it.completed) NextEpisode.Watch.COMPLETED else NextEpisode.Watch.IN_PROGRESS }
        }
    }
    val ambient = rememberAmbientColor(item.backdrop ?: item.poster, resolver)

    // The Continue button appears once episodes are known; move there unless the viewer already chose something else.
    val nextId = next?.episode?.id
    LaunchedEffect(nextId) {
        if (nextId != null && focus.lastFocusedKey in listOf(null, DetailTags.FAVORITE)) focus.requestFocus(DetailTags.PLAY)
    }

    DetailRoom(ambient) {
        item(key = "hero") {
            LuzHero(
                title = item.title,
                meta = listOfNotNull(
                    item.year?.toString(),
                    seasons.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.series_seasons, it, it) },
                    item.genres.take(2).joinToString(", ").ifEmpty { null },
                    detail?.ageRating,
                    item.rating?.let { "\u2605 $it" },
                ),
                badges = badgesOf(item.quality, item.tags, item.language),
                detail = item.plot ?: detail?.plot,
                detailLines = HERO_PLOT_LINES,
                modifier = Modifier.fillParentMaxHeight(Tokens.HERO_HEIGHT_FRACTION),
                room = ambient,
                artworkOf = item.backdrop ?: item.poster,
                actions = {
                    next?.let { pick ->
                        LuzButton(
                            stringResource(
                                if (pick.resume) R.string.series_continue else R.string.series_start,
                                pick.episode.seasonNumber,
                                pick.episode.episodeNumber,
                            ),
                            { onPlayEpisode(pick.episode.id, false) },
                            Modifier.rememberedFocus(focus, DetailTags.PLAY),
                            kind = ButtonKind.PRIMARY,
                            icon = LuzIcons.Play,
                        )
                    }
                    FavoriteButton(item.isFavorite, focus) {
                        coroutines.launch { graph.setFavorite(ContentType.SERIES, item.id, !item.isFavorite) }
                    }
                },
            ) { art, modifier -> Backdrop(art, resolver, modifier) }
        }
        when {
            list == null -> item(key = "status") {
                LuzStatusLine(stringResource(R.string.series_loading_episodes), Modifier.testTag(DetailTags.EPISODES_STATUS))
            }
            list.isEmpty() -> item(key = "status") {
                LuzStatusLine(
                    stringResource(if (failed) R.string.series_episodes_failed else R.string.series_no_episodes),
                    Modifier.testTag(DetailTags.EPISODES_STATUS),
                )
            }
            else -> {
                if (seasons.size > 1) {
                    item(key = "seasons") { SeasonTabs(seasons, shownSeason, focus) { season = it } }
                }
                val shown = list.filter { it.seasonNumber == shownSeason }
                item(key = "episodes-$shownSeason") {
                    val title = shownSeason?.let { stringResource(R.string.series_season, it) }.orEmpty()
                    LuzShelf(title = title) {
                        items(shown.size, key = { shown[it].id }) { index ->
                            EpisodeCard(shown[index], resolver, focus) { onPlayEpisode(shown[index].id, false) }
                        }
                    }
                }
            }
        }
        val info = detail
        val credits = creditsOf(info?.directors.orEmpty(), info?.cast.orEmpty())
        if (credits.isNotEmpty()) {
            item(key = "credits") { CreditsShelf(credits, focus, onPerson) }
        }
        item(key = "about") {
            AboutPanel(
                plot = item.plot ?: info?.plot,
                facts = factsOf(
                    item.genres, info?.releaseDate, item.year, null, info?.ageRating, item.rating, info?.country,
                    info?.directors.orEmpty(), info?.cast.orEmpty(),
                ),
                modifier = Modifier.rememberedFocus(focus, DetailTags.ABOUT),
            )
        }
    }
    RestoreFocusEffect(focus, DetailTags.PLAY)
}

/**
 * The page every detail screen is built in: the room, washed with the colour of the title's own picture, and a list
 * that moves calmly under the remote. [ambient] is that colour; without it — while the title is still loading — the
 * room is simply dark, so opening a page never flashes.
 */
@Composable
private fun DetailRoom(
    ambient: Color = Tokens.bgBase,
    heroFraction: Float = Tokens.HERO_HEIGHT_FRACTION,
    content: LazyListScope.() -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to ambient, heroFraction to ambient, 1f to Tokens.bgBase)),
    ) {
        CalmScrolling {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Tokens.shelfSpacing)) {
                content()
                item(key = "end") { Spacer(Modifier.height(Tokens.space10)) }
            }
        }
    }
}

@Composable
private fun Backdrop(art: UrlTemplate?, resolver: ((UrlTemplate) -> String?)?, modifier: Modifier) {
    ArtworkImage(art, resolver, null, modifier, BACKDROP_WIDTH_PX, BACKDROP_HEIGHT_PX)
}

@Composable
private fun FavoriteButton(favorite: Boolean, focus: FocusMemory, onToggle: () -> Unit) {
    LuzIconButton(
        if (favorite) LuzIcons.Check else LuzIcons.Add,
        stringResource(if (favorite) R.string.detail_remove_favorite else R.string.detail_add_favorite),
        onToggle,
        Modifier.rememberedFocus(focus, DetailTags.FAVORITE),
    )
}

/**
 * The seasons, as words: the one shown is white on a faint capsule, the one under the remote a white capsule. OK
 * shows that season's episodes on the shelf below.
 */
@Composable
private fun SeasonTabs(seasons: List<Int>, shown: Int?, focus: FocusMemory, onChoose: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.focusRestorer(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.space2),
        contentPadding = PaddingValues(start = Tokens.contentStart, end = Tokens.space16, top = Tokens.space2, bottom = Tokens.space2),
    ) {
        items(seasons.size, key = { seasons[it] }) { index ->
            val number = seasons[index]
            var focused by remember { mutableStateOf(false) }
            val selected = number == shown
            Text(
                stringResource(R.string.series_season, number),
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    focused -> Color.Black
                    selected -> Tokens.textPrimary
                    else -> Tokens.textSecondary
                },
                modifier = Modifier
                    .rememberedFocus(focus, DetailTags.season(number))
                    .clip(RoundedCornerShape(Tokens.radiusPill))
                    .background(
                        when {
                            focused -> Color.White
                            selected -> Tokens.raised
                            else -> Color.Transparent
                        },
                    )
                    .luzClickable(onClick = { onChoose(number) }, onFocus = { focused = it })
                    .padding(horizontal = Tokens.space4, vertical = Tokens.space2),
            )
        }
    }
}

/** An episode on the shelf: its still, "3 · The Title" under it, and how long it is — or a tick once it is watched. */
@Composable
private fun EpisodeCard(episode: EpisodeRow, resolver: ((UrlTemplate) -> String?)?, focus: FocusMemory, onPlay: () -> Unit) {
    val title = episode.title?.let { stringResource(R.string.series_episode_title, episode.episodeNumber, it) }
        ?: stringResource(R.string.series_episode_untitled, episode.episodeNumber)
    LuzCard(
        title = title,
        subtitle = episode.duration?.let { durationText(it) },
        shape = CardShape.LANDSCAPE,
        modifier = Modifier.rememberedFocus(focus, DetailTags.episode(episode.id)),
        progress = episode.progress?.takeIf { !it.completed }?.fraction,
        onClick = onPlay,
    ) { modifier ->
        Box(modifier) {
            ArtworkImage(episode.still, resolver, null, Modifier.fillMaxSize(), 640, 360)
            if (episode.progress?.completed == true) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Tokens.space2)
                        .size(WATCHED_MARK)
                        .clip(CircleShape)
                        .background(Tokens.scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(LuzIcons.Check, contentDescription = null, tint = Tokens.textPrimary, modifier = Modifier.size(WATCHED_ICON))
                }
            }
        }
    }
}

@Composable
private fun NotFound() {
    LuzEmptyState(title = stringResource(R.string.detail_not_found), message = null)
}

@Composable
internal fun durationText(duration: Duration): String {
    val minutes = duration.inWholeMinutes.toInt()
    return if (minutes >= 60) {
        stringResource(R.string.detail_duration_hours, minutes / 60, minutes % 60)
    } else {
        stringResource(R.string.detail_duration_minutes, minutes)
    }
}

private val WATCHED_MARK = 24.dp
private const val MOVIE_HERO_FRACTION = 0.86f
private const val HERO_PLOT_LINES = 3
private val WATCHED_ICON = 14.dp
