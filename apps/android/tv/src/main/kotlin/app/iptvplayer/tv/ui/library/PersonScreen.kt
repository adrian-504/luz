package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.RestoreFocusEffect
import app.iptvplayer.tv.ui.rememberFocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.CalmScrolling
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzEmptyState
import app.iptvplayer.tv.ui.theme.LuzShelf
import app.iptvplayer.tv.ui.theme.LuzSkeletonShelf
import app.iptvplayer.tv.ui.theme.Tokens

object PersonTags {
    fun movie(id: String) = "person-movie-$id"

    fun series(id: String) = "person-series-$id"
}

/**
 * Everything in the library with one person in it (ADR-0035): their name large, and their films and shows as shelves,
 * newest first. Reached from a film's Cast & Crew or from the People shelf in Search.
 */
@Composable
fun PersonScreen(playlistId: PlaylistId, name: String, onOpenMovie: (String) -> Unit, onOpenSeries: (String) -> Unit) {
    val graph = LocalAppGraph.current
    val focus = rememberFocusMemory()
    var titles by remember { mutableStateOf<Pair<List<MovieRow>, List<SeriesRow>>?>(null) }
    val detailRevision by graph.detailRevision.collectAsState()
    val fetching by graph.detailFetchRunning.collectAsState()
    LaunchedEffect(playlistId, name, detailRevision) { titles = graph.titlesOfPerson(playlistId, name) }
    val resolver = rememberArtworkResolver(playlistId)
    val found = titles
    val movies = found?.first.orEmpty().sortedByDescending { it.year ?: 0 }
    val series = found?.second.orEmpty().sortedByDescending { it.year ?: 0 }

    CalmScrolling {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Tokens.shelfSpacing),
            contentPadding = PaddingValues(top = Tokens.space10, bottom = Tokens.space10),
        ) {
            item(key = "name") {
                Column(Modifier.padding(start = Tokens.contentStart), verticalArrangement = Arrangement.spacedBy(Tokens.space2)) {
                    Text(name, style = MaterialTheme.typography.displayLarge, color = Tokens.textPrimary, maxLines = 1)
                    if (found != null) {
                        Text(
                            pluralStringResource(R.plurals.person_titles, movies.size + series.size, movies.size + series.size),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Tokens.textSecondary,
                        )
                    }
                    if (fetching) {
                        Text(
                            stringResource(R.string.person_still_reading),
                            style = MaterialTheme.typography.bodySmall,
                            color = Tokens.textTertiary,
                        )
                    }
                }
            }
            when {
                found == null -> item(key = "loading") { LuzSkeletonShelf(CardShape.POSTER) }
                movies.isEmpty() && series.isEmpty() -> item(key = "empty") {
                    LuzEmptyState(title = stringResource(R.string.person_nothing), message = null)
                }
                else -> {
                    if (movies.isNotEmpty()) {
                        item(key = "movies") {
                            LuzShelf(stringResource(R.string.search_movies)) {
                                items(movies.size, key = { movies[it].id }) { index ->
                                    val movie = movies[index]
                                    LuzCard(
                                        title = movie.title,
                                        subtitle = movie.year?.toString(),
                                        shape = CardShape.POSTER,
                                        modifier = Modifier.rememberedFocus(focus, PersonTags.movie(movie.id)),
                                        onClick = { onOpenMovie(movie.id) },
                                    ) { art -> ArtworkImage(movie.poster, resolver, movie.title, art) }
                                }
                            }
                        }
                    }
                    if (series.isNotEmpty()) {
                        item(key = "series") {
                            LuzShelf(stringResource(R.string.search_series)) {
                                items(series.size, key = { series[it].id }) { index ->
                                    val show = series[index]
                                    LuzCard(
                                        title = show.title,
                                        subtitle = show.year?.toString(),
                                        shape = CardShape.POSTER,
                                        modifier = Modifier.rememberedFocus(focus, PersonTags.series(show.id)),
                                        onClick = { onOpenSeries(show.id) },
                                    ) { art -> ArtworkImage(show.poster, resolver, show.title, art) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    val first = movies.firstOrNull()?.let { PersonTags.movie(it.id) } ?: series.firstOrNull()?.let { PersonTags.series(it.id) }
    if (first != null) RestoreFocusEffect(focus, first)
}
