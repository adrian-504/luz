package app.iptvplayer.storage

import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.Episode
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.Season
import app.iptvplayer.domain.model.Series
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant
import kotlin.time.toDuration

/** Library category (movie or series group). */
public data class LibraryGroupRow(public val id: String, public val title: String, public val itemCount: Long)

/** Saved playback progress of one movie or episode (DOMAIN_MODEL.md WatchState). */
public data class WatchProgress(public val position: Duration, public val duration: Duration?, public val completed: Boolean) {
    /** 0.0–1.0 when the duration is known. */
    public val fraction: Float? get() = duration?.takeIf { it.isPositive() }?.let { (position / it).toFloat().coerceIn(0f, 1f) }
}

public data class MovieRow(
    public val id: String,
    public val title: String,
    public val year: Int?,
    public val duration: Duration?,
    public val plot: String?,
    public val genres: List<String>,
    public val rating: String?,
    public val poster: UrlTemplate?,
    public val backdrop: UrlTemplate?,
    public val addedAt: Instant?,
    public val progress: WatchProgress?,
    public val isFavorite: Boolean,
)

public data class SeriesRow(
    public val id: String,
    public val title: String,
    public val year: Int?,
    public val plot: String?,
    public val genres: List<String>,
    public val rating: String?,
    public val poster: UrlTemplate?,
    public val backdrop: UrlTemplate?,
    public val providerSeriesId: String?,
    public val isFavorite: Boolean,
)

public data class SeasonRow(public val id: String, public val number: Int, public val title: String?, public val poster: UrlTemplate?)

public data class EpisodeRow(
    public val id: String,
    public val seriesId: String,
    public val seasonNumber: Int,
    public val episodeNumber: Int,
    public val title: String?,
    public val plot: String?,
    public val duration: Duration?,
    public val still: UrlTemplate?,
    public val progress: WatchProgress?,
)

/** A movie or episode to continue, most recently played first. */
public data class ContinueItem(
    public val type: ContentType,
    public val id: String,
    public val parentId: String?,
    public val progress: WatchProgress,
)

/**
 * Movies and series of a source (ROADMAP Phase 8). Each unit (MOVIES, SERIES) is imported into its own snapshot and published
 * atomically like live channels; Xtream seasons and episodes are added to the active SERIES snapshot when a series is opened.
 * Watch state is user state keyed by stable content ids, so it survives refreshes.
 */
public class LibraryStore(private val content: ContentStore, private val clock: Clock) {
    private val queries = content.libraryQueries

    private fun active(playlistId: PlaylistId, unit: ImportUnit): Long? = content.unitState(playlistId, unit)?.activeSnapshot

    public fun beginSnapshot(playlistId: PlaylistId, unit: ImportUnit, batchSize: Int = 500): LibrarySnapshotWriter {
        require(unit == ImportUnit.MOVIES || unit == ImportUnit.SERIES) { "library units are MOVIES and SERIES" }
        return LibrarySnapshotWriter(playlistId, unit, content.allocateSnapshot(playlistId), batchSize)
    }

    public fun groups(playlistId: PlaylistId, unit: ImportUnit): List<LibraryGroupRow> {
        val snapshot = active(playlistId, unit) ?: return emptyList()
        return queries.libraryGroups(playlistId.value, snapshot, unit.name).executeAsList()
            .map { LibraryGroupRow(it.id, it.title, it.item_count) }
    }

    public fun movies(playlistId: PlaylistId, groupId: String? = null, limit: Int = Int.MAX_VALUE, offset: Int = 0): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        val (pageSize, skip) = limit.toLong() to offset.toLong()
        return if (groupId == null) {
            queries.movies(playlistId.value, snapshot, pageSize, skip).executeAsList().map {
                movieRow(
                    it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template,
                    it.backdrop_template, it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite,
                )
            }
        } else {
            queries.moviesInGroup(playlistId.value, snapshot, groupId, pageSize, skip).executeAsList().map {
                movieRow(
                    it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template,
                    it.backdrop_template, it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite,
                )
            }
        }
    }

    public fun recentMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return queries.recentMovies(playlistId.value, snapshot, limit.toLong()).executeAsList().map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template,
                it.backdrop_template, it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite,
            )
        }
    }

    public fun movie(playlistId: PlaylistId, id: String): MovieRow? {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return null
        return queries.movieById(playlistId.value, snapshot, id).executeAsOneOrNull()?.let {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template,
                it.backdrop_template, it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite,
            )
        }
    }

    public fun movieCount(playlistId: PlaylistId): Long =
        active(playlistId, ImportUnit.MOVIES)?.let { queries.movieCount(playlistId.value, it).executeAsOne() } ?: 0

    public fun series(playlistId: PlaylistId, groupId: String? = null, limit: Int = Int.MAX_VALUE, offset: Int = 0): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        val (pageSize, skip) = limit.toLong() to offset.toLong()
        return if (groupId == null) {
            queries.seriesList(playlistId.value, snapshot, pageSize, skip).executeAsList().map {
                seriesRow(
                    it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                    it.provider_series_id, it.is_favorite,
                )
            }
        } else {
            queries.seriesInGroup(playlistId.value, snapshot, groupId, pageSize, skip).executeAsList().map {
                seriesRow(
                    it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                    it.provider_series_id, it.is_favorite,
                )
            }
        }
    }

    public fun seriesById(playlistId: PlaylistId, id: String): SeriesRow? {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return null
        return queries.seriesById(playlistId.value, snapshot, id).executeAsOneOrNull()?.let {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.provider_series_id, it.is_favorite,
            )
        }
    }

    public fun seriesCount(playlistId: PlaylistId): Long =
        active(playlistId, ImportUnit.SERIES)?.let { queries.seriesCount(playlistId.value, it).executeAsOne() } ?: 0

    /** Movies matching [query] through the title index, best match first (FR-SRCH-002). */
    public fun searchMovies(playlistId: PlaylistId, query: String, limit: Int): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        val ids = content.searchTitles(playlistId, snapshot, ContentType.MOVIE, query, limit)
        if (ids.isEmpty()) return emptyList()
        val found = queries.moviesByIds(playlistId.value, snapshot, ids).executeAsList().associateBy { it.id }
        return ids.mapNotNull { id ->
            found[id]?.let {
                movieRow(
                    it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template,
                    it.backdrop_template, it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite,
                )
            }
        }
    }

    /** Series matching [query] through the title index, best match first (FR-SRCH-002). */
    public fun searchSeries(playlistId: PlaylistId, query: String, limit: Int): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        val ids = content.searchTitles(playlistId, snapshot, ContentType.SERIES, query, limit)
        if (ids.isEmpty()) return emptyList()
        val found = queries.seriesByIds(playlistId.value, snapshot, ids).executeAsList().associateBy { it.id }
        return ids.mapNotNull { id ->
            found[id]?.let {
                seriesRow(
                    it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                    it.provider_series_id, it.is_favorite,
                )
            }
        }
    }

    /** Titles for [ids] of [unit], including hidden ones — the hidden list has to name what it offers back. */
    public fun titles(playlistId: PlaylistId, unit: ImportUnit, ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val snapshot = active(playlistId, unit) ?: return emptyMap()
        return if (unit == ImportUnit.MOVIES) {
            queries.movieNames(playlistId.value, snapshot, ids).executeAsList().associate { it.id to it.title }
        } else {
            queries.seriesNames(playlistId.value, snapshot, ids).executeAsList().associate { it.id to it.title }
        }
    }

    public fun seasons(playlistId: PlaylistId, seriesId: String): List<SeasonRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return queries.seasonsOfSeries(playlistId.value, snapshot, seriesId).executeAsList()
            .map { SeasonRow(it.id, it.season_number.toInt(), it.title, it.poster_template?.let(::UrlTemplate)) }
    }

    public fun episodes(playlistId: PlaylistId, seriesId: String): List<EpisodeRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return queries.episodesOfSeries(playlistId.value, snapshot, seriesId).executeAsList().map {
            episodeRow(
                it.id, it.series_id, it.season_number, it.episode_number, it.title, it.plot, it.duration_seconds, it.still_template,
                it.position_ms, it.watched_duration_ms, it.completed,
            )
        }
    }

    public fun episode(playlistId: PlaylistId, id: String): EpisodeRow? {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return null
        return queries.episodeById(playlistId.value, snapshot, id).executeAsOneOrNull()?.let {
            episodeRow(
                it.id, it.series_id, it.season_number, it.episode_number, it.title, it.plot, it.duration_seconds, it.still_template,
                it.position_ms, it.watched_duration_ms, it.completed,
            )
        }
    }

    /** True when seasons and episodes of [seriesId] are stored for the active series snapshot. */
    public fun hasSeriesDetail(playlistId: PlaylistId, seriesId: String): Boolean {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return false
        return queries.seriesDetailState(playlistId.value, seriesId).executeAsOneOrNull()?.snapshot == snapshot
    }

    /**
     * Replaces the seasons and episodes of one series in the active series snapshot (Xtream `get_series_info` on first open).
     * Returns false when no series snapshot is active.
     */
    public fun replaceSeriesDetail(
        playlistId: PlaylistId,
        seriesId: String,
        seasons: List<Season>,
        seasonPosters: Map<String, UrlTemplate?>,
        episodes: List<Triple<Episode, MediaSource, UrlTemplate?>>,
    ): Boolean {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return false
        content.transaction {
            val previous = queries.episodesOfSeries(playlistId.value, snapshot, seriesId).executeAsList().map { it.id }
            if (previous.isNotEmpty()) previous.chunked(500).forEach { queries.deleteMediaSourcesForOwners(playlistId.value, snapshot, it) }
            queries.deleteSeriesDetail(playlistId.value, snapshot, seriesId)
            queries.deleteSeriesSeasons(playlistId.value, snapshot, seriesId)
            seasons.forEach { writeSeason(playlistId, snapshot, it, seasonPosters[it.id.value]) }
            val numbers = seasons.associate { it.id.value to it.seasonNumber }
            episodes.forEachIndexed { index, (episode, source, still) ->
                writeEpisode(playlistId, snapshot, episode, numbers[episode.seasonId.value] ?: 0, source, still, index.toLong())
            }
            queries.setSeriesDetailState(playlistId.value, seriesId, snapshot, clock.now().toEpochMilliseconds())
        }
        return true
    }

    public fun mediaSources(playlistId: PlaylistId, type: ContentType, id: String): List<MediaSource> {
        val unit = if (type == ContentType.MOVIE) ImportUnit.MOVIES else ImportUnit.SERIES
        val snapshot = active(playlistId, unit) ?: return emptyList()
        return content.mediaSources(playlistId, snapshot, type, id)
    }

    /**
     * Saves progress for a movie or episode. Completed at 95 % of a known duration (DOMAIN_MODEL.md); [newSession] counts a
     * play. Positions under 10 seconds from the start are stored as 0 so a quick look does not appear in "Continue watching".
     */
    public fun saveProgress(
        playlistId: PlaylistId,
        type: ContentType,
        id: String,
        parentId: String?,
        position: Duration,
        duration: Duration?,
        ended: Boolean = false,
        newSession: Boolean = false,
    ) {
        require(type == ContentType.MOVIE || type == ContentType.EPISODE) { "progress is saved for movies and episodes" }
        val completed = ended || (duration != null && duration.isPositive() && position >= duration * COMPLETED_FRACTION)
        val stored = if (completed || position < MIN_RESUME_POSITION) Duration.ZERO else position
        queries.upsertWatchState(
            type.name, id, playlistId.value, parentId, stored.inWholeMilliseconds, duration?.inWholeMilliseconds,
            if (completed) 1L else 0L, clock.now().toEpochMilliseconds(), newSession,
        )
    }

    public fun progress(type: ContentType, id: String): WatchProgress? =
        queries.watchState(type.name, id).executeAsOneOrNull()?.let { progressOf(it.position_ms, it.duration_ms, it.completed) }

    /** Movies and episodes started but not finished, most recent first. */
    public fun continueWatching(playlistId: PlaylistId, limit: Int): List<ContinueItem> =
        queries.inProgress(playlistId.value, limit.toLong()).executeAsList().mapNotNull { row ->
            val progress = progressOf(row.position_ms, row.duration_ms, row.completed) ?: return@mapNotNull null
            ContinueItem(ContentType.valueOf(row.content_type), row.content_id, row.parent_id, progress)
        }

    /** The episode of [seriesId] played most recently, if any. */
    public fun lastWatchedEpisode(seriesId: String): Pair<String, WatchProgress>? =
        queries.lastWatchedEpisodeOfSeries(seriesId).executeAsOneOrNull()?.let { row ->
            progressOf(row.position_ms, row.duration_ms, row.completed)?.let { row.content_id to it }
        }

    private fun writeSeason(playlistId: PlaylistId, snapshot: Long, season: Season, poster: UrlTemplate?) {
        queries.insertSeason(
            playlistId.value,
            snapshot,
            season.seriesId.value,
            season.id.value,
            season.seasonNumber.toLong(),
            season.title,
            poster?.template,
            season.episodeCount?.toLong(),
        )
    }

    private fun writeEpisode(
        playlistId: PlaylistId,
        snapshot: Long,
        episode: Episode,
        seasonNumber: Int,
        source: MediaSource,
        still: UrlTemplate?,
        order: Long,
    ) {
        queries.insertEpisode(
            playlistId.value, snapshot, episode.seriesId.value, episode.seasonId.value, episode.id.value, seasonNumber.toLong(),
            episode.episodeNumber.toLong(), order, episode.title, episode.plot, episode.duration?.inWholeSeconds, episode.airDate,
            still?.template,
        )
        content.insertMediaSource(playlistId, snapshot, source)
    }

    /** Writes one MOVIES or SERIES snapshot in batched transactions; nothing is visible until [publish]. */
    public inner class LibrarySnapshotWriter internal constructor(
        public val playlistId: PlaylistId,
        public val unit: ImportUnit,
        public val snapshot: Long,
        private val batchSize: Int,
    ) {
        private val pending = ArrayList<() -> Unit>(batchSize)
        private var groupOrder = 0L
        private var itemOrder = 0L
        private var memberOrder = 0L
        private var episodeOrder = 0L

        // Episodes reference their season by id; M3U emits each season before its episodes.
        private val seasonNumbers = HashMap<String, Int>()
        public var itemCount: Int = 0
            private set

        public fun group(group: ChannelGroup) {
            val order = groupOrder++
            add { queries.insertLibraryGroup(playlistId.value, snapshot, unit.name, group.id.value, group.title, order) }
        }

        public fun movie(movie: Movie, source: MediaSource, poster: UrlTemplate?, backdrop: UrlTemplate? = null) {
            val order = itemOrder++
            itemCount++
            add {
                content.searchQueries.insertTitle(
                    movie.title,
                    ContentType.MOVIE.name,
                    playlistId.value,
                    snapshot.toString(),
                    movie.id.value,
                )
                queries.insertMovie(
                    playlistId.value, snapshot, movie.id.value, movie.title, order, movie.year?.toLong(), movie.duration?.inWholeSeconds,
                    movie.plot, encodeList(movie.genres), movie.rating, poster?.template, backdrop?.template,
                    movie.addedAt?.toEpochMilliseconds(),
                )
                content.insertMediaSource(playlistId, snapshot, source)
            }
            movie.groupIds.forEach { member(movie.id.value, it.value) }
        }

        public fun series(series: Series, poster: UrlTemplate?, backdrop: UrlTemplate?) {
            val order = itemOrder++
            itemCount++
            add {
                content.searchQueries.insertTitle(
                    series.title,
                    ContentType.SERIES.name,
                    playlistId.value,
                    snapshot.toString(),
                    series.id.value,
                )
                queries.insertSeries(
                    playlistId.value, snapshot, series.id.value, series.title, order, series.year?.toLong(), series.plot,
                    encodeList(series.genres), series.rating, poster?.template, backdrop?.template, series.providerSeriesId,
                    series.lastModifiedAt?.toEpochMilliseconds(),
                )
            }
            series.groupIds.forEach { member(series.id.value, it.value) }
        }

        /** Seasons and episodes imported with the series list (M3U playlists). */
        public fun season(season: Season, poster: UrlTemplate?) {
            seasonNumbers[season.id.value] = season.seasonNumber
            add { writeSeason(playlistId, snapshot, season, poster) }
        }

        public fun episode(episode: Episode, source: MediaSource, still: UrlTemplate?) {
            val order = episodeOrder++
            val number = seasonNumbers[episode.seasonId.value] ?: 0
            add { writeEpisode(playlistId, snapshot, episode, number, source, still, order) }
        }

        private fun member(itemId: String, groupId: String) {
            val order = memberOrder++
            add { queries.insertLibraryMember(playlistId.value, snapshot, groupId, itemId, order) }
        }

        private fun add(write: () -> Unit) {
            pending += write
            if (pending.size >= batchSize) flush()
        }

        private fun flush() {
            if (pending.isEmpty()) return
            content.transaction { pending.forEach { it() } }
            pending.clear()
        }

        /** Makes this snapshot the one readers see and deletes the previous one of this unit. */
        public fun publish() {
            flush()
            content.transaction {
                val previous = active(playlistId, unit)
                content.publishUnit(playlistId, unit, snapshot, itemCount.toLong())
                if (previous != null && previous != snapshot) deleteSnapshot(previous)
            }
            content.checkpoint()
        }

        public fun discard() {
            pending.clear()
            content.transaction { deleteSnapshot(snapshot) }
        }

        private fun deleteSnapshot(version: Long) {
            // The title index stores the snapshot as text (Search.sq), so it is cleared separately.
            content.searchQueries.deleteTitlesOfSnapshot(playlistId.value, version.toString())
            queries.deleteLibrarySnapshot(playlistId.value, version)
        }
    }

    private companion object {
        const val COMPLETED_FRACTION = 0.95
        val MIN_RESUME_POSITION = 10.seconds

        fun encodeList(values: List<String>): String? =
            values.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map(::JsonPrimitive)).toString() }

        fun decodeList(text: String?): List<String> =
            text?.let { runCatching { Json.parseToJsonElement(it).jsonArray.map { e -> e.jsonPrimitive.content } }.getOrNull() }.orEmpty()

        fun progressOf(position: Long?, duration: Long?, completed: Long?): WatchProgress? {
            if (position == null) return null
            return WatchProgress(position.milliseconds(), duration?.milliseconds(), completed == 1L)
        }

        fun Long.milliseconds(): Duration = this.toDuration(DurationUnit.MILLISECONDS)

        fun movieRow(
            id: String,
            title: String,
            year: Long?,
            duration: Long?,
            plot: String?,
            genres: String?,
            rating: String?,
            poster: String?,
            backdrop: String?,
            added: Long?,
            position: Long?,
            watched: Long?,
            completed: Long?,
            favorite: Boolean?,
        ) = MovieRow(
            id, title, year?.toInt(), duration?.seconds, plot, decodeList(genres), rating, poster?.let(::UrlTemplate),
            backdrop?.let(::UrlTemplate),
            added?.let(Instant::fromEpochMilliseconds), progressOf(position, watched ?: duration?.times(1000), completed), favorite == true,
        )

        fun seriesRow(
            id: String,
            title: String,
            year: Long?,
            plot: String?,
            genres: String?,
            rating: String?,
            poster: String?,
            backdrop: String?,
            provider: String?,
            favorite: Boolean?,
        ) = SeriesRow(
            id, title, year?.toInt(), plot, decodeList(genres), rating, poster?.let(::UrlTemplate), backdrop?.let(::UrlTemplate), provider,
            favorite == true,
        )

        fun episodeRow(
            id: String,
            seriesId: String,
            season: Long,
            number: Long,
            title: String?,
            plot: String?,
            duration: Long?,
            still: String?,
            position: Long?,
            watched: Long?,
            completed: Long?,
        ) = EpisodeRow(
            id, seriesId, season.toInt(), number.toInt(), title, plot, duration?.seconds, still?.let(::UrlTemplate),
            progressOf(position, watched ?: duration?.times(1000), completed),
        )
    }
}
