package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.NextEpisode
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.storage.EpisodeRow
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.ActionButton
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.player.clock
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.Tokens
import kotlinx.coroutines.launch
import kotlin.time.Duration

object DetailTags {
    const val PLAY = "detail-play"
    const val PLAY_FROM_START = "detail-play-from-start"
    const val FAVORITE = "detail-favorite"
    const val EPISODES_STATUS = "detail-episodes-status"

    fun season(number: Int) = "detail-season-$number"

    fun episode(id: String) = "detail-episode-$id"
}

/** Movie detail (FR-VOD-001): poster, facts, plot; Play or Resume, Play from the beginning, favorite. */
@Composable
fun MovieDetailScreen(playlistId: PlaylistId, movieId: String, onPlay: (fromStart: Boolean) -> Unit) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    val watched by graph.watchRevision.collectAsState()
    val focus = rememberFocusMemory()
    var movie by remember { mutableStateOf<MovieRow?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(revision, watched) {
        movie = graph.movie(playlistId, movieId)
        loaded = true
    }
    val resolver = rememberArtworkResolver(playlistId)
    if (!loaded) return
    val item = movie ?: return NotFound()
    val resume = item.progress?.takeIf { !it.completed && it.position.isPositive() }

    DetailLayout(
        poster = { ArtworkImage(item.poster, resolver, item.title, Modifier.width(260.dp).height(390.dp)) },
        title = item.title,
        facts = listOfNotNull(
            item.year?.toString(),
            item.duration?.let {
                durationText(it)
            },
            item.genres.take(3).joinToString(", ").ifEmpty { null },
            item.rating,
        ),
        plot = item.plot,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4)) {
            ActionButton(
                if (resume !=
                    null
                ) {
                    stringResource(R.string.detail_resume, clock(resume.position.inWholeMilliseconds))
                } else {
                    stringResource(R.string.detail_play)
                },
                { onPlay(false) },
                Modifier.rememberedFocus(focus, DetailTags.PLAY),
            )
            if (resume != null) {
                ActionButton(
                    stringResource(R.string.detail_play_from_start),
                    { onPlay(true) },
                    Modifier.rememberedFocus(focus, DetailTags.PLAY_FROM_START),
                )
            }
            ActionButton(
                stringResource(if (item.isFavorite) R.string.detail_remove_favorite else R.string.detail_add_favorite),
                { coroutines.launch { graph.setFavorite(ContentType.MOVIE, item.id, !item.isFavorite) } },
                Modifier.rememberedFocus(focus, DetailTags.FAVORITE),
            )
        }
        item.progress?.fraction?.takeIf { resume != null }?.let {
            WatchedBar(
                it,
                Modifier.widthIn(max = 520.dp).padding(top = Tokens.space2),
            )
        }
    }
    RestoreFocusEffect(focus, DetailTags.PLAY)
}

/**
 * Series detail (FR-SER-001): facts and plot, a Continue button for the next episode (NextEpisode), seasons and their episodes.
 * Xtream episodes load from the provider the first time a series is opened.
 */
@Composable
fun SeriesDetailScreen(playlistId: PlaylistId, seriesId: String, onPlayEpisode: (episodeId: String, fromStart: Boolean) -> Unit) {
    val graph = LocalAppGraph.current
    val coroutines = rememberCoroutineScope()
    val revision by graph.revision.collectAsState()
    val watched by graph.watchRevision.collectAsState()
    val focus = rememberFocusMemory()
    var series by remember { mutableStateOf<SeriesRow?>(null) }
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
        }
    }
    val resolver = rememberArtworkResolver(playlistId)
    if (!loaded) return
    val item = series ?: return NotFound()
    val list = episodes
    val seasons = list.orEmpty().map { it.seasonNumber }.distinct()
    val shownSeason = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
    val next = list?.let { all ->
        NextEpisode.pick(all, all.firstOrNull { it.id == lastWatchedId }) { episode ->
            episode.progress?.let { if (it.completed) NextEpisode.Watch.COMPLETED else NextEpisode.Watch.IN_PROGRESS }
        }
    }

    // The Continue button appears once episodes are known; move there unless the user already chose something else.
    val nextId = next?.episode?.id
    LaunchedEffect(nextId) {
        if (nextId != null && focus.lastFocusedKey in listOf(null, DetailTags.FAVORITE)) focus.requestFocus(DetailTags.PLAY)
    }

    DetailLayout(
        poster = { ArtworkImage(item.poster, resolver, item.title, Modifier.width(220.dp).height(330.dp)) },
        title = item.title,
        facts = listOfNotNull(item.year?.toString(), item.genres.take(3).joinToString(", ").ifEmpty { null }, item.rating),
        plot = item.plot,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.space4)) {
            next?.let { pick ->
                ActionButton(
                    stringResource(
                        if (pick.resume) R.string.series_continue else R.string.series_start,
                        pick.episode.seasonNumber,
                        pick.episode.episodeNumber,
                    ),
                    { onPlayEpisode(pick.episode.id, false) },
                    Modifier.rememberedFocus(focus, DetailTags.PLAY),
                )
            }
            ActionButton(
                stringResource(if (item.isFavorite) R.string.detail_remove_favorite else R.string.detail_add_favorite),
                { coroutines.launch { graph.setFavorite(ContentType.SERIES, item.id, !item.isFavorite) } },
                Modifier.rememberedFocus(focus, DetailTags.FAVORITE),
            )
        }
        when {
            list == null -> StatusText(stringResource(R.string.series_loading_episodes))
            list.isEmpty() -> StatusText(stringResource(if (failed) R.string.series_episodes_failed else R.string.series_no_episodes))
            else -> {
                if (seasons.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tokens.space3), modifier = Modifier.focusRestorer()) {
                        items(seasons.size, key = { seasons[it] }) { index ->
                            val number = seasons[index]
                            ListItem(
                                selected = number == shownSeason,
                                onClick = { season = number },
                                headlineContent = { Text(stringResource(R.string.series_season, number)) },
                                modifier = Modifier.width(160.dp).rememberedFocus(focus, DetailTags.season(number)),
                            )
                        }
                    }
                }
                val shown = list.filter { it.seasonNumber == shownSeason }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Tokens.space2),
                    modifier = Modifier.widthIn(max = 900.dp).focusRestorer(),
                ) {
                    items(shown.size, key = { shown[it].id }) { index ->
                        val episode = shown[index]
                        ListItem(
                            selected = false,
                            onClick = { onPlayEpisode(episode.id, false) },
                            headlineContent = {
                                Text(
                                    episode.title?.let { stringResource(R.string.series_episode_title, episode.episodeNumber, it) }
                                        ?: stringResource(R.string.series_episode_untitled, episode.episodeNumber),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Column(verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                                    episode.duration?.let { Text(durationText(it), style = MaterialTheme.typography.bodySmall) }
                                    WatchedBar(episode.progress?.takeIf { !it.completed }?.fraction)
                                }
                            },
                            trailingContent = if (episode.progress?.completed == true) {
                                { Text("✓", style = MaterialTheme.typography.titleMedium) }
                            } else {
                                null
                            },
                            modifier = Modifier.rememberedFocus(focus, DetailTags.episode(episode.id)),
                        )
                    }
                }
            }
        }
    }
    RestoreFocusEffect(focus, DetailTags.PLAY)
}

@Composable
private fun DetailLayout(
    poster: @Composable () -> Unit,
    title: String,
    facts: List<String>,
    plot: String?,
    actions: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Tokens.bgBase)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(
                horizontal = Tokens.safeHorizontal + Tokens.space8,
                vertical =
                Tokens.safeVertical + Tokens.space6,
            ),
            horizontalArrangement = Arrangement.spacedBy(Tokens.space8),
        ) {
            poster()
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.space4), modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Tokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tokens.textSecondary,
                    )
                }
                plot?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tokens.textSecondary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 900.dp),
                    )
                }
                actions()
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Tokens.textSecondary,
        modifier = Modifier.testTag(DetailTags.EPISODES_STATUS),
    )
}

@Composable
private fun NotFound() {
    Box(Modifier.fillMaxSize().background(Tokens.bgBase).padding(Tokens.safeHorizontal)) {
        Text(stringResource(R.string.detail_not_found), style = MaterialTheme.typography.titleLarge, color = Tokens.textSecondary)
    }
}

@Composable
internal fun durationText(duration: Duration): String {
    val minutes = duration.inWholeMinutes.toInt()
    return if (minutes >=
        60
    ) {
        stringResource(R.string.detail_duration_hours, minutes / 60, minutes % 60)
    } else {
        stringResource(R.string.detail_duration_minutes, minutes)
    }
}
