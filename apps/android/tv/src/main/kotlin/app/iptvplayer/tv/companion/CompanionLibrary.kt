package app.iptvplayer.tv.companion

import android.content.Context
import android.graphics.Bitmap
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.domain.sport.MatchCalendar
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.GroupRow
import app.iptvplayer.storage.MovieRow
import app.iptvplayer.storage.NowNextRow
import app.iptvplayer.storage.SeriesRow
import app.iptvplayer.tv.R
import app.iptvplayer.tv.app.AppGraph
import app.iptvplayer.tv.app.ContinueCard
import app.iptvplayer.tv.ui.library.BrowseFilter
import app.iptvplayer.tv.ui.library.Shelf
import app.iptvplayer.tv.ui.library.ShelfItem
import app.iptvplayer.tv.ui.library.browseMovies
import app.iptvplayer.tv.ui.library.browseSeries
import app.iptvplayer.tv.ui.library.buildShelves
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

/** What a change to a list came to: done (with the list's name, its old name after a rename, and how many it holds), or why not. */
sealed interface ListResult {
    data class Done(val id: String, val title: String?, val count: Int, val previous: String? = null) : ListResult
    data class NameTaken(val title: String) : ListResult
    data object NotFound : ListResult
    data object Missing : ListResult
}

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
        val continuing = graph.continueCards(p, ROW).map(::continueCard)
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
            .put("lists", JSONArray(graph.userGroups(p).map(::listLine)))
            .put("favourites", favourites.size)
            .put("myList", JSONArray(graph.favoriteMovies(p, ROW).map(::poster) + graph.favoriteSeries(p, ROW).map(::poster)))
            .put("added", JSONArray(graph.recentMovies(p, ROW).map(::poster)))
            .put("newEpisodes", JSONArray(graph.newEpisodeSeries(p, ROW).map(::poster)))
    }

    private fun continueCard(card: ContinueCard): JSONObject = JSONObject()
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

    /**
     * Films or Series on the phone (2c, Design 2.16): the TV's own shelves in its order, at most [SHELF_ITEMS] titles each
     * ("continue" with the continue fields), its genres, decades and the provider's categories with their counts, and how
     * many titles the library holds. Null for a kind that is neither.
     */
    suspend fun library(kind: String): JSONObject? {
        val unit = unitOf(kind) ?: return null
        val p = playlist() ?: return JSONObject().put("source", false)
        val movies = unit == ImportUnit.MOVIES
        val shelves = buildShelves(graph, context.resources, p, unit)
        val titles = shelves.filterIsInstance<Shelf.Titles>().map { shelf ->
            val items = if (shelf.key == CONTINUE) {
                graph.continueCards(p, SHELF_ITEMS)
                    .filter { it.type == if (movies) ContentType.MOVIE else ContentType.EPISODE }
                    .map(::continueCard)
            } else {
                shelf.items.take(SHELF_ITEMS).map { item -> shelfPoster(kind, item) }
            }
            JSONObject().put("key", shelf.key).put("title", shelf.title).put("wide", shelf.key == CONTINUE).put("items", JSONArray(items))
        }
        fun tiles(key: String, prefix: String) = JSONArray(
            shelves.filterIsInstance<Shelf.Tiles>().firstOrNull { it.key == key }?.tiles.orEmpty()
                .filter { it.key.startsWith(prefix) }
                .map { tile ->
                    JSONObject()
                        .put("id", tile.key.removePrefix(prefix))
                        .put("title", tile.title)
                        .put("count", tile.count ?: JSONObject.NULL)
                },
        )
        val groups = graph.libraryGroups(p, unit)
        return JSONObject()
            .put("source", true)
            .put("kind", kind)
            .put("total", groups.sumOf { it.itemCount })
            .put("shelves", JSONArray(titles))
            .put("genres", tiles("genres", "genre:"))
            .put("decades", tiles("decades", "decade:"))
            .put("categories", JSONArray(groups.map { JSONObject().put("id", it.id).put("title", it.title).put("count", it.itemCount) }))
    }

    /**
     * One page of a poster grid (2c): [filter] is "all", "genre:<name>", "decade:<year>" or "category:<id>", in the TV
     * grid's order. Null when the kind is neither or the filter names something that is gone.
     */
    suspend fun browse(kind: String, filter: String, offset: Int, limit: Int): JSONObject? {
        val unit = unitOf(kind) ?: return null
        val p = playlist() ?: return null
        val movies = unit == ImportUnit.MOVIES
        val f = BrowseFilter(filter)
        val page = limit.coerceIn(1, MAX_PAGE)
        val from = offset.coerceAtLeast(0)
        val (title, total) = when {
            filter == ALL -> context.getString(if (movies) R.string.library_all_movies else R.string.library_all_series) to
                graph.libraryGroups(p, unit).sumOf { it.itemCount }
            f.category != null -> graph.libraryGroups(p, unit).firstOrNull { it.id == f.category }?.let { it.title to it.itemCount }
                ?: return null
            f.genre != null -> {
                val genres = if (movies) graph.movieGenres(p, Int.MAX_VALUE) else graph.seriesGenres(p, Int.MAX_VALUE)
                genres.firstOrNull { it.first == f.genre }?.let { it.first to it.second } ?: return null
            }
            f.decade != null && movies ->
                graph.movieDecades(p).firstOrNull { it.first == f.decade }
                    ?.let { context.getString(R.string.library_decade, it.first) to it.second }
                    ?: return null
            else -> return null
        }
        val items = if (movies) {
            browseMovies(graph, p, filter, page, from).map(::poster)
        } else {
            browseSeries(graph, p, filter, page, from).map(::poster)
        }
        return JSONObject().put("title", title).put("total", total).put("items", JSONArray(items))
    }

    private fun shelfPoster(kind: String, item: ShelfItem): JSONObject = JSONObject()
        .put("kind", kind)
        .put("id", item.id)
        .put("title", item.title)
        .put("year", item.year ?: JSONObject.NULL)
        .put("art", ref(if (kind == "movie") MOVIE else SERIES, item.id, item.poster))
        .put("fraction", item.progress ?: JSONObject.NULL)

    private fun unitOf(kind: String): ImportUnit? = when (kind) {
        "movie" -> ImportUnit.MOVIES
        "series" -> ImportUnit.SERIES
        else -> null
    }

    /**
     * The phone's Matches tab (s3, ADR-0057): followed matches for 14 days, with their teams' three letters, the league,
     * kick-off (epoch ms), whether one is under way and whether it has a reminder. No channel, ever. Empty when nothing is
     * followed, so the page can hide the tab.
     */
    suspend fun sport(): JSONObject {
        val now = Clock.System.now()
        val matches = graph.followedMatches(now).map { card ->
            val f = card.fixture
            JSONObject()
                .put("id", f.id)
                .put("home", f.homeName)
                .put("away", f.awayName)
                .put("homeTla", card.home?.tla ?: JSONObject.NULL)
                .put("awayTla", card.away?.tla ?: JSONObject.NULL)
                .put("competition", f.competitionName)
                .put("kickoff", f.kickoff.toEpochMilliseconds())
                .put("live", f.kickoff <= now)
                .put("reminder", card.reminder)
        }
        return JSONObject().put("follows", graph.follows().size).put("matches", JSONArray(matches))
    }

    /** The same matches as a calendar file (RFC 5545) for the phone's calendar: "Add all" adds them, adding again updates. */
    suspend fun calendar(): String {
        val now = Clock.System.now()
        val matches = graph.followedMatches(now).map { card ->
            val f = card.fixture
            MatchCalendar.Match(f.id, context.getString(R.string.match_teams, f.homeName, f.awayName), f.competitionName, f.kickoff)
        }
        return MatchCalendar.ics(matches, now)
    }

    /** A reminder set or cleared from the phone; false when the match is not one Luz has. */
    suspend fun setReminder(id: String, on: Boolean): Boolean {
        if (graph.fixture(id) == null) return false
        graph.remind(id, on)
        return true
    }

    suspend fun group(id: String): JSONObject? {
        val p = playlist() ?: return null
        val group = graph.groups(p).firstOrNull { it.id == id } ?: return null
        return JSONObject()
            .put("title", group.title)
            .put("scope", "g:$id")
            .put("items", channels(p, graph.channels(p, id).take(GROUP_CHANNELS)))
    }

    /**
     * Search, as on the TV. With [list] (a list's id, or [FAVOURITES]), every result says whether it is already in it, for
     * the phone's Add / Added (Design 2.8); favourites hold channels only, so only channels come back for them.
     */
    suspend fun search(query: String, list: String? = null): JSONObject {
        val result = JSONObject().put("query", query)
        val p = playlist()
        if (p == null || query.isBlank()) return result.put("channels", JSONArray()).put("movies", JSONArray()).put("series", JSONArray())
        val found = graph.search(p, query.trim().take(MAX_QUERY), SEARCH)
        if (list == null) {
            return result
                .put("channels", channels(p, found.channels))
                .put("movies", JSONArray(found.movies.map(::poster)))
                .put("series", JSONArray(found.series.map(::poster)))
        }
        if (list == FAVOURITES) {
            return result
                .put("channels", channels(p, found.channels) { it.isFavorite })
                .put("movies", JSONArray())
                .put("series", JSONArray())
        }
        val held = members(p, list)
        return result
            .put("channels", channels(p, found.channels) { ContentType.CHANNEL to it.id.value in held })
            .put("movies", JSONArray(found.movies.map { poster(it).put("added", ContentType.MOVIE to it.id in held) }))
            .put("series", JSONArray(found.series.map { poster(it).put("added", ContentType.SERIES to it.id in held) }))
    }

    /** One of the viewer's lists, or their favourite channels ([FAVOURITES]); null when it is gone. */
    suspend fun list(id: String): JSONObject? {
        val p = playlist() ?: return null
        if (id == FAVOURITES) {
            return JSONObject().put("id", FAVOURITES).put("title", JSONObject.NULL)
                .put("channels", channels(p, graph.favoriteChannels(p))).put("movies", JSONArray()).put("series", JSONArray())
        }
        val list = graph.userGroups(p).firstOrNull { it.id == id } ?: return null
        return JSONObject()
            .put("id", list.id)
            .put("title", list.title)
            .put("channels", channels(p, graph.channelsInUserGroup(p, id)))
            .put("movies", JSONArray(graph.moviesInUserGroup(p, id, LIST_TITLES).map(::poster)))
            .put("series", JSONArray(graph.seriesInUserGroup(p, id, LIST_TITLES).map(::poster)))
    }

    /** What the phone's "⋯" sheet needs about one channel, film or series: its favourite or My List state and its lists. */
    suspend fun item(kind: String, id: String): JSONObject? {
        val p = playlist() ?: return null
        val type = type(kind) ?: return null
        val base = JSONObject().put("kind", kind).put("id", id)
        when (type) {
            ContentType.CHANNEL -> {
                val channel = graph.channelRow(p, ChannelId(id)) ?: return null
                base.put("title", channel.label).put("number", channel.number ?: JSONObject.NULL)
                    // The provider's category name is not looked up yet; the phone leaves the line out when it is null.
                    .put("category", JSONObject.NULL).put("favorite", channel.isFavorite)
            }
            ContentType.MOVIE -> graph.movie(p, id)?.let { base.put("title", it.title).put("favorite", it.isFavorite) } ?: return null
            else -> graph.seriesById(p, id)?.let { base.put("title", it.title).put("favorite", it.isFavorite) } ?: return null
        }
        val holding = graph.groupsHolding(p, type, id).toSet()
        return base.put(
            "lists",
            JSONArray(graph.userGroups(p).map { JSONObject().put("id", it.id).put("title", it.title).put("in", it.id in holding) }),
        )
    }

    /** The name of a channel, film or series, for the TV's note; null when it is not in the source. */
    suspend fun itemName(kind: String, id: String): String? {
        val p = playlist() ?: return null
        return when (type(kind)) {
            ContentType.CHANNEL -> graph.channelLabel(p, ChannelId(id))
            ContentType.MOVIE -> graph.movie(p, id)?.title
            ContentType.SERIES -> graph.seriesById(p, id)?.title
            else -> null
        }
    }

    /** Lists the phone can change; each returns what the TV's note and the phone need, or a [ListResult] error. */
    suspend fun createList(title: String?, add: Pair<String, String>?): ListResult {
        val p = playlist() ?: return ListResult.NotFound
        val existing = graph.userGroups(p).map { it.title }
        val name = title?.trim()?.take(MAX_TITLE)?.ifEmpty { null }
        if (name != null && existing.any { it.equals(name, ignoreCase = true) }) return ListResult.NameTaken(name)
        val chosen = name ?: graph.unnamedListTitle(existing)
        val type = add?.let { type(it.first) ?: return ListResult.Missing }
        if (add != null && itemName(add.first, add.second) == null) return ListResult.NotFound
        val id = graph.createGroup(p, chosen) ?: return ListResult.NotFound
        if (type != null) graph.addToGroup(p, id, type, add.second)
        return ListResult.Done(id, chosen, if (type != null) 1 else 0)
    }

    suspend fun renameList(id: String, title: String): ListResult {
        val p = playlist() ?: return ListResult.NotFound
        val name = title.trim().take(MAX_TITLE).ifEmpty { return ListResult.Missing }
        val lists = graph.userGroups(p)
        val list = lists.firstOrNull { it.id == id } ?: return ListResult.NotFound
        if (lists.any { it.id != id && it.title.equals(name, ignoreCase = true) }) return ListResult.NameTaken(name)
        graph.renameGroup(p, id, name)
        return ListResult.Done(id, name, 0, previous = list.title)
    }

    suspend fun deleteList(id: String): ListResult {
        val p = playlist() ?: return ListResult.NotFound
        val list = graph.userGroups(p).firstOrNull { it.id == id } ?: return ListResult.NotFound
        graph.deleteGroup(p, id)
        return ListResult.Done(id, list.title, 0)
    }

    /** Puts a channel, film or series in a list or takes it out; [FAVOURITES] works for channels. */
    suspend fun setInList(id: String, kind: String, item: String, on: Boolean): ListResult {
        val p = playlist() ?: return ListResult.NotFound
        val type = type(kind) ?: return ListResult.Missing
        if (itemName(kind, item) == null) return ListResult.NotFound
        if (id == FAVOURITES) {
            if (type != ContentType.CHANNEL) return ListResult.Missing
            graph.setFavorite(ChannelId(item), on)
            return ListResult.Done(FAVOURITES, null, graph.favoriteChannels(p).size)
        }
        val list = graph.userGroups(p).firstOrNull { it.id == id } ?: return ListResult.NotFound
        if (on) graph.addToGroup(p, id, type, item) else graph.removeFromGroup(p, id, type, item)
        val after = graph.userGroups(p).firstOrNull { it.id == id }
        return ListResult.Done(id, list.title, after?.let { (it.channelCount + it.movieCount + it.seriesCount).toInt() } ?: 0)
    }

    /**
     * A short fingerprint of the lists and favourites, so the server tells the phone ("lists" event) only when one of them
     * changed, not on every change to the library.
     */
    suspend fun listsStamp(): String {
        val p = playlist() ?: return ""
        val lists = graph.userGroups(p).joinToString("|") { "${it.id}:${it.title}:${it.channelCount}:${it.movieCount}:${it.seriesCount}" }
        return "$p#$lists#${graph.favoriteChannels(p).joinToString(",") { it.id.value }}"
    }

    /** Changes to the library, from the TV or the phone, as a number that goes up. */
    val revision: Int get() = graph.revision.value

    private suspend fun members(p: PlaylistId, list: String): Set<Pair<ContentType, String>> = buildSet {
        graph.channelsInUserGroup(p, list).forEach { add(ContentType.CHANNEL to it.id.value) }
        graph.moviesInUserGroup(p, list, LIST_TITLES).forEach { add(ContentType.MOVIE to it.id) }
        graph.seriesInUserGroup(p, list, LIST_TITLES).forEach { add(ContentType.SERIES to it.id) }
    }

    private fun type(kind: String): ContentType? = when (kind) {
        "channel" -> ContentType.CHANNEL
        "movie" -> ContentType.MOVIE
        "series" -> ContentType.SERIES
        else -> null
    }

    private fun listLine(row: GroupRow): JSONObject = JSONObject()
        .put("id", row.id)
        .put("title", row.title)
        .put("channels", row.channelCount)
        .put("movies", row.movieCount)
        .put("series", row.seriesCount)

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

    private suspend fun channels(p: PlaylistId, rows: List<ChannelRow>, added: ((ChannelRow) -> Boolean)? = null): JSONArray {
        val guide = graph.storedNowNext(p, rows, Clock.System.now())
        return JSONArray(rows.map { row -> channel(row, guide[row.id.value]).also { if (added != null) it.put("added", added(row)) } })
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
        private const val SHELF_ITEMS = 20
        private const val MAX_PAGE = 120
        private const val CONTINUE = "continue"
        private const val ALL = "all"
        const val MOVIE = "m"
        const val MOVIE_WIDE = "mw"
        const val SERIES = "s"
        const val SERIES_WIDE = "sw"
        const val EPISODE = "e"
        const val CHANNEL = "c"

        /** The favourite channels, addressed like a list (`/api/list?id=favorites`). */
        const val FAVOURITES = "favorites"

        /** A picture's reference, as the phone hands it back to `/api/art`. */
        fun art(kind: String, id: String): String = "$kind:$id"

        private const val ROW = 20
        private const val CHANNELS = 60
        private const val GROUP_CHANNELS = 400
        private const val SEARCH = 30
        private const val MAX_QUERY = 100
        private const val MAX_TITLE = 60
        private const val LIST_TITLES = 500
        private const val POSTER_W = 300
        private const val POSTER_H = 450
        private const val WIDE_W = 640
        private const val WIDE_H = 360
        private const val LOGO_PX = 160
        private const val JPEG_QUALITY = 80
    }
}
