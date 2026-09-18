package app.iptvplayer.tv.ui.library

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.ui.FocusMemory
import app.iptvplayer.tv.ui.rememberedFocus
import app.iptvplayer.tv.ui.theme.CardShape
import app.iptvplayer.tv.ui.theme.LuzCard
import app.iptvplayer.tv.ui.theme.LuzShelf
import kotlin.time.Duration.Companion.minutes

/** One Explore shelf: films or shows, all from the viewer's own library. */
class ExploreShelf(val key: String, val title: String, val titles: List<ExploreTitle>)

class ExploreTitle(val id: String, val title: String, val year: Int?, val poster: UrlTemplate?, val series: Boolean)

/** The last Explore built for each source, kept while Luz runs, so Search opens on it at once. */
private object ExploreMemory {
    val last = HashMap<PlaylistId, List<ExploreShelf>>()
}

/**
 * Explore (the owner's idea 9): what Search shows before anything is typed — ways into the library for a viewer who does
 * not know what they want. Every shelf is built on the television from the viewer's own titles: what is trending among
 * them, the best rated, films of about ninety minutes, the decades, the genres Movies does not already put on a shelf,
 * and the best-rated shows. Shelves appear as they are read.
 */
@Composable
fun rememberExplore(graph: AppGraph, playlist: PlaylistId, revision: Int): List<ExploreShelf>? {
    val resources = LocalResources.current
    var shelves by remember(playlist) { mutableStateOf(ExploreMemory.last[playlist]) }
    LaunchedEffect(playlist, revision) {
        val built = mutableListOf<ExploreShelf>()
        fun movies(key: String, title: String, rows: List<app.iptvplayer.storage.MovieRow>) {
            if (rows.size < MIN_TITLES) return
            built += ExploreShelf(key, title, rows.map { ExploreTitle(it.id, it.title, it.year, it.poster, series = false) })
            if (ExploreMemory.last[playlist] == null) shelves = built.toList()
        }
        val trending = graph.moviesOfList(playlist, ExternalList.TRENDING_MOVIES, LIMIT)
        if (trending.isNotEmpty()) {
            movies("trending", resources.getString(R.string.explore_trending), trending)
        } else {
            movies("popular", resources.getString(R.string.explore_popular), graph.popularMovies(playlist, LIMIT))
        }
        movies("top", resources.getString(R.string.explore_top), graph.topRatedMovies(playlist, LIMIT))
        movies("ninety", resources.getString(R.string.explore_ninety), graph.moviesOfLength(playlist, 80.minutes, 100.minutes, LIMIT))
        val decades = graph.movieDecades(playlist).toMap()
        for (decade in DECADES) {
            if ((decades[decade] ?: 0) < MIN_TITLES) continue
            movies("decade-$decade", resources.getString(R.string.explore_decade, decade), graph.moviesOfDecade(playlist, decade, LIMIT))
        }
        // Movies already has shelves for the six largest genres; Explore offers the next ones.
        graph.movieGenres(playlist, GENRES_SEEN + GENRES_OFFERED).drop(GENRES_SEEN).forEach { (genre, _) ->
            movies("genre-$genre", genre, graph.moviesOfGenre(playlist, genre, LIMIT))
        }
        val shows = graph.topRatedSeries(playlist, LIMIT)
        if (shows.size >= MIN_TITLES) {
            built += ExploreShelf(
                "top-shows",
                resources.getString(R.string.explore_top_shows),
                shows.map {
                    ExploreTitle(it.id, it.title, it.year, it.poster, series = true)
                },
            )
        }
        shelves = built.toList()
        ExploreMemory.last[playlist] = built.toList()
    }
    return shelves
}

object ExploreTags {
    fun title(shelf: String, id: String) = "explore-$shelf-$id"
}

/** Explore's shelves as items of Search's list. */
fun LazyListScope.exploreItems(
    shelves: List<ExploreShelf>,
    resolver: ((UrlTemplate) -> String?)?,
    focus: FocusMemory,
    onOpenMovie: (String) -> Unit,
    onOpenSeries: (String) -> Unit,
) {
    shelves.forEach { shelf ->
        item(key = "explore-${shelf.key}") {
            LuzShelf(shelf.title) {
                items(shelf.titles.size, key = { shelf.titles[it].id }) { index ->
                    val title = shelf.titles[index]
                    LuzCard(
                        title = title.title,
                        subtitle = title.year?.toString(),
                        shape = CardShape.POSTER,
                        modifier = Modifier.rememberedFocus(focus, ExploreTags.title(shelf.key, title.id)),
                        onClick = { if (title.series) onOpenSeries(title.id) else onOpenMovie(title.id) },
                    ) { art -> ArtworkImage(title.poster, resolver, title.title, art) }
                }
            }
        }
    }
}

private const val LIMIT = 20
private const val MIN_TITLES = 4
private const val GENRES_SEEN = 6
private const val GENRES_OFFERED = 4
private val DECADES = listOf(2010, 2000, 1990, 1980, 1970)
