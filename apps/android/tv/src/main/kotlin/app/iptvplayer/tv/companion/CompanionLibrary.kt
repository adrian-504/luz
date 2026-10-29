package app.iptvplayer.tv.companion

import android.content.Context
import android.graphics.Bitmap
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.tv.app.AppGraph
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

/** A picture for the phone: its bytes and their type. */
class CompanionPicture(val bytes: ByteArray, val type: String)

/**
 * What the phone companion browses (ADR-0050, step 3): the viewer's channels, films and series from the current source, as
 * plain JSON. Only names, numbers, times and ids leave the TV; pictures go through [picture], so a provider's address,
 * which can carry the login, never reaches the phone.
 */
class CompanionLibrary(private val context: Context, private val graph: AppGraph) {
    private suspend fun playlist(): PlaylistId? = graph.currentSource()?.playlistId

    suspend fun home(): JSONObject {
        val p = playlist() ?: return JSONObject().put("source", false)
        val favourites = graph.favoriteChannels(p)
        val channels = favourites.ifEmpty { graph.mostWatchedChannels(p, CHANNELS) }
        val continuing = graph.continueCards(p, ROW).map { card ->
            JSONObject()
                .put("kind", if (card.type == ContentType.MOVIE) "movie" else "episode")
                .put("id", card.id)
                .put("page", card.pageId)
                .put("title", card.title)
                .put("subtitle", card.subtitle ?: JSONObject.NULL)
                .put("fraction", card.fraction ?: JSONObject.NULL)
                .put("remainingMin", card.remaining?.inWholeMinutes ?: JSONObject.NULL)
                .put("art", ref(if (card.type == ContentType.MOVIE) MOVIE else SERIES, card.pageId, card.poster))
                .put("wide", ref(if (card.type == ContentType.MOVIE) MOVIE_WIDE else SERIES_WIDE, card.pageId, card.backdrop))
                .put("needsEpisodes", card.needsEpisodes)
        }
        return JSONObject()
            .put("source", true)
            .put("continue", JSONArray(continuing))
            .put(
                "channels",
                JSONObject()
                    .put("title", if (favourites.isNotEmpty()) "Favourites" else "Watched most")
                    .put("scope", if (favourites.isNotEmpty()) "favorites" else "all")
                    .put("items", channels(p, channels.take(CHANNELS))),
            )
            .put(
                "groups",
                JSONArray(
                    graph.groups(p).filter { it.channelCount > 0 }.map {
                        JSONObject().put("id", it.id).put("title", it.title).put("count", it.channelCount)
                    },
                ),
            )
            .put("myList", JSONArray(graph.favoriteMovies(p, ROW).map(::poster) + graph.favoriteSeries(p, ROW).map(::poster)))
            .put("added", JSONArray(graph.recentMovies(p, ROW).map(::poster)))
            .put("newEpisodes", JSONArray(graph.newEpisodeSeries(p, ROW).map(::poster)))
    }

    suspend fun group(id: String): JSONObject? {
        val p = playlist() ?: return null
        val group = graph.groups(p).firstOrNull { it.id == id } ?: return null
        return JSONObject()
            .put("title", group.title)
            .put("scope", "g:$id")
            .put("items", channels(p, graph.channels(p, id).take(GROUP_CHANNELS)))
    }

    suspend fun search(query: String): JSONObject {
        val result = JSONObject().put("query", query)
        val p = playlist()
        if (p == null || query.isBlank()) return result.put("channels", JSONArray()).put("movies", JSONArray()).put("series", JSONArray())
        val found = graph.search(p, query.trim().take(MAX_QUERY), SEARCH)
        return result
            .put("channels", channels(p, found.channels))
            .put("movies", JSONArray(found.movies.map(::poster)))
            .put("series", JSONArray(found.series.map(::poster)))
    }

    suspend fun movie(id: String): JSONObject? {
        val p = playlist() ?: return null
        val movie = graph.movie(p, id) ?: return null
        return JSONObject()
            .put("id", movie.id)
            .put("title", movie.title)
            .put("year", movie.year ?: JSONObject.NULL)
            .put("minutes", movie.duration?.inWholeMinutes ?: JSONObject.NULL)
            .put("rating", movie.rating ?: JSONObject.NULL)
            .put("genres", JSONArray(movie.genres))
            .put("plot", movie.plot ?: JSONObject.NULL)
            .put("fraction", movie.progress?.takeUnless { it.completed }?.fraction ?: JSONObject.NULL)
            .put("favorite", movie.isFavorite)
            .put("art", ref(MOVIE, movie.id, movie.poster))
            .put("wide", ref(MOVIE_WIDE, movie.id, movie.backdrop))
    }

    /** A series with its episodes, asked of the provider the first time (as opening its page on the TV does). */
    suspend fun series(id: String): JSONObject? {
        val p = playlist() ?: return null
        val series = graph.seriesById(p, id) ?: return null
        var episodes = graph.episodes(p, id)
        if (episodes.isEmpty() && graph.loadSeriesDetail(p, id)) episodes = graph.episodes(p, id)
        val resume = graph.lastWatchedEpisodeId(id)?.let { last -> episodes.firstOrNull { it.id == last } }
        val seasons = episodes.groupBy { it.seasonNumber }.toSortedMap().map { (number, list) ->
            JSONObject().put("number", number).put(
                "episodes",
                JSONArray(
                    list.sortedBy { it.episodeNumber }.map { episode ->
                        JSONObject()
                            .put("id", episode.id)
                            .put("number", episode.episodeNumber)
                            .put("title", episode.title ?: JSONObject.NULL)
                            .put("minutes", episode.duration?.inWholeMinutes ?: JSONObject.NULL)
                            .put("fraction", episode.progress?.takeUnless { it.completed }?.fraction ?: JSONObject.NULL)
                            .put("watched", episode.progress?.completed == true)
                            .put("still", ref(EPISODE, episode.id, episode.still))
                    },
                ),
            )
        }
        return JSONObject()
            .put("id", series.id)
            .put("title", series.title)
            .put("year", series.year ?: JSONObject.NULL)
            .put("rating", series.rating ?: JSONObject.NULL)
            .put("genres", JSONArray(series.genres))
            .put("plot", series.plot ?: JSONObject.NULL)
            .put("favorite", series.isFavorite)
            .put("art", ref(SERIES, series.id, series.poster))
            .put("wide", ref(SERIES_WIDE, series.id, series.backdrop))
            .put(
                "resume",
                resume?.let { JSONObject().put("id", it.id).put("label", "S${it.seasonNumber} E${it.episodeNumber}") } ?: JSONObject.NULL,
            )
            .put("seasons", JSONArray(seasons))
    }

    /**
     * What the phone asked to play or open, ready for the TV, with the name the TV's note shows ("Playing … · from your
     * phone"); null when the source or the title is not there.
     */
    suspend fun request(kind: String, id: String, scope: String?, fromStart: Boolean): Pair<CompanionRequest, String>? {
        val p = playlist() ?: return null
        return when (kind) {
            "channel" -> graph.channelLabel(p, ChannelId(id))?.let { CompanionRequest.Channel(p, scope ?: "all", ChannelId(id)) to it }
            "movie" -> graph.movie(p, id)?.let { CompanionRequest.Play(p, ContentType.MOVIE, id, fromStart) to it.title }
            "episode" -> graph.episode(p, id)?.let { episode ->
                val show = graph.seriesById(p, episode.seriesId)?.title
                CompanionRequest.Play(p, ContentType.EPISODE, id, fromStart) to
                    listOfNotNull(show, "S${episode.seasonNumber} E${episode.episodeNumber}").joinToString(" ")
            }
            "movie-page" -> graph.movie(p, id)?.let { CompanionRequest.Movie(p, id) to it.title }
            "series" -> graph.seriesById(p, id)?.let { CompanionRequest.Series(p, id) to it.title }
            else -> null
        }
    }

    suspend fun setMyList(kind: String, id: String, on: Boolean): Boolean {
        val type = when (kind) {
            "movie" -> ContentType.MOVIE
            "series" -> ContentType.SERIES
            else -> return false
        }
        graph.setFavorite(type, id, on)
        return true
    }

    /**
     * A picture named by a [ref] this class handed out, fetched and decoded on the TV through the app's own image cache
     * (keyed by the stored template, as on screen) and sent on small: a poster at phone size, a logo with its transparency.
     */
    suspend fun picture(ref: String): CompanionPicture? {
        val p = playlist() ?: return null
        val kind = ref.substringBefore(':')
        val id = ref.substringAfter(':', "").ifEmpty { return null }
        val template = when (kind) {
            MOVIE -> graph.movie(p, id)?.poster
            MOVIE_WIDE -> graph.movie(p, id)?.backdrop
            SERIES -> graph.seriesById(p, id)?.poster
            SERIES_WIDE -> graph.seriesById(p, id)?.backdrop
            EPISODE -> graph.episode(p, id)?.still
            CHANNEL -> graph.channelLogo(p, ChannelId(id))
            else -> null
        } ?: return null
        val url = graph.artworkResolver(p)(template) ?: return null
        val (width, height) = when (kind) {
            MOVIE, SERIES -> POSTER_W to POSTER_H
            CHANNEL -> LOGO_PX to LOGO_PX
            else -> WIDE_W to WIDE_H
        }
        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey("${template.template}#phone")
            .diskCacheKey(template.template)
            .size(width, height)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        val bitmap = result.image.toBitmap()
        val out = ByteArrayOutputStream()
        return if (kind == CHANNEL) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            CompanionPicture(out.toByteArray(), "image/png")
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            CompanionPicture(out.toByteArray(), "image/jpeg")
        }
    }

    private suspend fun channels(p: PlaylistId, rows: List<ChannelRow>): JSONArray {
        val guide = graph.storedNowNext(p, rows, Clock.System.now())
        return JSONArray(rows.map { channel(it, guide[it.id.value]) })
    }

    private fun channel(row: ChannelRow, guide: NowNextRow?): JSONObject = JSONObject()
        .put("id", row.id.value)
        .put("name", row.label)
        .put("number", row.number ?: JSONObject.NULL)
        .put("quality", row.quality ?: JSONObject.NULL)
        .put("logo", ref(CHANNEL, row.id.value, row.logo))
        .put(
            "now",
            guide?.current?.let {
                JSONObject().put("title", it.title).put("start", it.start.toEpochMilliseconds()).put("end", it.end.toEpochMilliseconds())
            } ?: JSONObject.NULL,
        )
        .put(
            "next",
            guide?.next?.let { JSONObject().put("title", it.title).put("start", it.start.toEpochMilliseconds()) } ?: JSONObject.NULL,
        )

    private fun poster(movie: MovieRow): JSONObject = JSONObject()
        .put("kind", "movie")
        .put("id", movie.id)
        .put("title", movie.title)
        .put("year", movie.year ?: JSONObject.NULL)
        .put("art", ref(MOVIE, movie.id, movie.poster))
        .put("fraction", movie.progress?.takeUnless { it.completed }?.fraction ?: JSONObject.NULL)

    private fun poster(series: SeriesRow): JSONObject = JSONObject()
        .put("kind", "series")
        .put("id", series.id)
        .put("title", series.title)
        .put("year", series.year ?: JSONObject.NULL)
        .put("art", ref(SERIES, series.id, series.poster))
        .put("fraction", JSONObject.NULL)

    /** A picture's name for the phone: what it shows and whose it is, never where it lives. */
    private fun ref(kind: String, id: String, template: UrlTemplate?): Any = if (template == null) JSONObject.NULL else art(kind, id)

    companion object {
        const val MOVIE = "m"
        const val MOVIE_WIDE = "mw"
        const val SERIES = "s"
        const val SERIES_WIDE = "sw"
        const val EPISODE = "e"
        const val CHANNEL = "c"

        /** A picture's reference, as the phone hands it back to `/api/art`. */
        fun art(kind: String, id: String): String = "$kind:$id"

        private const val ROW = 20
        private const val CHANNELS = 60
        private const val GROUP_CHANNELS = 400
        private const val SEARCH = 30
        private const val MAX_QUERY = 100
        private const val POSTER_W = 300
        private const val POSTER_H = 450
        private const val WIDE_W = 640
        private const val WIDE_H = 360
        private const val LOGO_PX = 160
        private const val JPEG_QUALITY = 80
    }
}
