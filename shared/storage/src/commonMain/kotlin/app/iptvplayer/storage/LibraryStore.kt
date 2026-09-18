package app.iptvplayer.storage

import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.library.Quality
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.Episode
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.Movie
import app.iptvplayer.domain.model.Season
import app.iptvplayer.domain.model.Series
import app.iptvplayer.domain.model.TitleDetail
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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

    /** What is left to watch, when the length is known. */
    public val remaining: Duration? get() = duration?.takeIf { it.isPositive() }?.let { (it - position).coerceAtLeast(Duration.ZERO) }
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
    /** Badges taken out of the provider's title. */
    public val quality: Quality? = null,
    public val tags: List<String> = emptyList(),
    public val language: String? = null,
    /** How many versions of this film the provider lists (1 when it is the only one). */
    public val versionCount: Int = 1,
    /** The provider's TMDB id; read only for a single film ([LibraryStore.movie]). */
    public val tmdbId: String? = null,
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
    public val quality: Quality? = null,
    public val tags: List<String> = emptyList(),
    public val language: String? = null,
    /** When the provider last changed the show — in practice, when its newest episode arrived. */
    public val lastModifiedAt: Instant? = null,
    /** The provider's TMDB id; read only for a single show ([LibraryStore.seriesById]). */
    public val tmdbId: String? = null,
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

/** A film's or show's page as the provider sent it (TitleDetail), with when it was fetched. */
public data class TitleDetailRow(
    public val fetchedAt: Instant,
    public val plot: String?,
    public val genres: List<String>,
    public val duration: Duration?,
    public val releaseDate: String?,
    public val year: Int?,
    public val poster: UrlTemplate?,
    public val backdrop: UrlTemplate?,
    public val cast: List<String>,
    public val directors: List<String>,
    public val trailer: String?,
    public val country: String?,
    public val ageRating: String?,
    public val rating: String?,
    public val tmdbId: String?,
)

/** One entry of an outside list (ADR-0038), as stored: its rank is its place in the list. */
public data class ListEntry(
    public val tmdbId: Long,
    public val type: ContentType,
    public val title: String,
    public val year: Int?,
    public val rating: Double?,
    public val votes: Int?,
    public val tmdbKey: String,
    public val titleKey: String,
    public val bareTitleKey: String = titleKey,
)

/** TMDB artwork stored for a work: its id there (null when TMDB did not know it) and its title artwork. */
public data class StoredArt(
    public val tmdbId: Long?,
    public val logoPath: String?,
    public val fetchedAt: Instant,
    public val backdropPath: String? = null,
)

/** A person's portrait as learned from a title's credits on TMDB. */
public data class Portrait(public val name: String, public val tmdbId: Long, public val profilePath: String?)

/** A person as stored from TMDB: a biography once their own page has been read. */
public data class StoredPerson(
    public val tmdbId: Long?,
    public val profilePath: String?,
    public val biography: String?,
    public val fetchedAt: Instant,
)

/** A person found by search, with how many of the viewer's films and shows they are in. */
public data class PersonHit(public val name: String, public val titles: Int, public val directs: Boolean)

/** One version of a film when the provider lists several. */
public data class MovieVersionRow(
    public val id: String,
    public val title: String,
    public val quality: Quality?,
    public val tags: List<String>,
    public val language: String?,
)

/** How much of each detail field the provider filled, for the check in Settings. */
public data class DetailCoverage(
    public val type: ContentType,
    public val fetched: Long,
    public val plot: Long,
    public val genres: Long,
    public val cast: Long,
    public val directors: Long,
    public val trailer: Long,
    public val backdrop: Long,
    public val duration: Long,
    public val rating: Long,
    public val ageRating: Long,
    public val country: Long,
    public val releaseDate: Long,
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
    private val detailQueries = content.detailQueries
    private val browseQueries = content.browseQueries
    private val tmdbQueries = content.tmdbQueries

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
                    it.quality, it.tags, it.language, it.version_count,
                )
            }
        } else {
            queries.moviesInGroup(playlistId.value, snapshot, groupId, pageSize, skip).executeAsList().map {
                movieRow(
                    it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template,
                    it.backdrop_template, it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite,
                    it.quality, it.tags, it.language, it.version_count,
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
                it.quality, it.tags, it.language, it.version_count,
            )
        }
    }

    public fun movie(playlistId: PlaylistId, id: String): MovieRow? {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return null
        return queries.movieById(playlistId.value, snapshot, id).executeAsOneOrNull()?.let {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template,
                it.backdrop_template, it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite,
                it.quality, it.tags, it.language, it.version_count,
            ).copy(tmdbId = it.tmdb_id)
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
                    it.provider_series_id, it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
                )
            }
        } else {
            queries.seriesInGroup(playlistId.value, snapshot, groupId, pageSize, skip).executeAsList().map {
                seriesRow(
                    it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                    it.provider_series_id, it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
                )
            }
        }
    }

    public fun seriesById(playlistId: PlaylistId, id: String): SeriesRow? {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return null
        return queries.seriesById(playlistId.value, snapshot, id).executeAsOneOrNull()?.let {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.provider_series_id, it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            ).copy(tmdbId = it.tmdb_id)
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
                    it.quality, it.tags, it.language, it.version_count,
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
                    it.provider_series_id, it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
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

    // ---- Details, people and genres (ADR-0035) ----

    /**
     * Stores what the provider sent about a film or show. A null [detail] records that it was asked and had nothing, so the
     * background fetch does not ask again; genres and people are indexed for shelves and search.
     */
    public fun saveDetail(playlistId: PlaylistId, type: ContentType, id: String, detail: TitleDetail?) {
        content.transaction { writeDetail(playlistId, type, id, detail ?: TitleDetail()) }
    }

    private fun writeDetail(playlistId: PlaylistId, type: ContentType, id: String, detail: TitleDetail) {
        detailQueries.upsertDetail(
            playlistId.value, type.name, id, clock.now().toEpochMilliseconds(), detail.plot, encodeList(detail.genres),
            detail.duration?.inWholeSeconds, detail.releaseDate, detail.year?.toLong(), detail.poster?.template, detail.backdrop?.template,
            encodeList(detail.cast), encodeList(detail.directors), detail.trailer, detail.country, detail.ageRating, detail.rating,
            detail.tmdbId,
        )
        detailQueries.deleteGenresOf(playlistId.value, type.name, id)
        detail.genres.forEach { detailQueries.insertGenre(playlistId.value, type.name, id, it) }
        detailQueries.deletePeopleOf(playlistId.value, type.name, id)
        detail.cast.forEach { detailQueries.insertTitlePerson(playlistId.value, type.name, id, personId(playlistId, it), ROLE_CAST) }
        detail.directors.forEach {
            detailQueries.insertTitlePerson(
                playlistId.value,
                type.name,
                id,
                personId(playlistId, it),
                ROLE_DIRECTOR,
            )
        }
        when (type) {
            ContentType.MOVIE -> queries.applyPageToMovie(
                ratingValue(detail.rating),
                detail.backdrop?.template,
                detail.plot,
                encodeList(detail.genres),
                detail.duration?.inWholeSeconds,
                detail.year?.toLong(),
                playlistId.value,
                id,
            )
            ContentType.SERIES -> ratingValue(detail.rating)?.let { queries.setSeriesRating(it, playlistId.value, id) }
            else -> Unit
        }
    }

    /** The id of [name] in this source, adding the person — and their name to the search index — the first time. */
    private fun personId(playlistId: PlaylistId, name: String): Long =
        detailQueries.personId(playlistId.value, name).executeAsOneOrNull() ?: run {
            detailQueries.insertPerson(playlistId.value, name)
            val id = detailQueries.personId(playlistId.value, name).executeAsOne()
            detailQueries.insertPersonName(name, id.toString())
            id
        }

    public fun detail(playlistId: PlaylistId, type: ContentType, id: String): TitleDetailRow? =
        detailQueries.detailOf(playlistId.value, type.name, id).executeAsOneOrNull()?.let {
            TitleDetailRow(
                Instant.fromEpochMilliseconds(it.fetched_at), it.plot, decodeList(it.genres), it.duration_seconds?.seconds, it.release_date,
                it.year?.toInt(), it.poster_template?.let(::UrlTemplate), it.backdrop_template?.let(::UrlTemplate),
                decodeList(it.cast_names),
                decodeList(it.directors), it.trailer, it.country, it.age_rating, it.rating, it.tmdb_id,
            )
        }

    /** Films still without a fetched page, newest first, with the provider stream id to ask for: (film id, stream id). */
    public fun moviesMissingDetail(playlistId: PlaylistId, limit: Int): List<Pair<String, String>> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return detailQueries.moviesMissingDetail(playlistId.value, snapshot, limit.toLong()).executeAsList()
            .map { row -> row.id to row.stream_id }
    }

    public fun movieStreamId(playlistId: PlaylistId, movieId: String): String? {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return null
        return detailQueries.movieStreamId(playlistId.value, snapshot, movieId).executeAsOneOrNull()
    }

    public fun detailCoverage(playlistId: PlaylistId): List<DetailCoverage> =
        detailQueries.detailCoverage(playlistId.value).executeAsList().mapNotNull { row ->
            val type = ContentType.entries.firstOrNull { it.name == row.content_type } ?: return@mapNotNull null
            DetailCoverage(
                type, row.fetched, row.with_plot.count(), row.with_genres.count(), row.with_cast.count(), row.with_directors.count(),
                row.with_trailer.count(), row.with_backdrop.count(), row.with_duration.count(), row.with_rating.count(),
                row.with_age_rating.count(),
                row.with_country.count(), row.with_release_date.count(),
            )
        }

    /** People whose name matches [query], most titles first. */
    public fun searchPeople(playlistId: PlaylistId, query: String, limit: Int): List<PersonHit> {
        val match = TitleIndex.match(query) ?: return emptyList()
        return detailQueries.searchPeople(match, PEOPLE_CANDIDATES, playlistId.value).executeAsList()
            .map { PersonHit(it.name, it.titles.toInt(), (it.directs ?: 0L) > 0) }
            .sortedWith(
                compareByDescending<PersonHit> { it.name.startsWith(query.trim(), ignoreCase = true) }.thenByDescending { it.titles },
            )
            .take(limit)
    }

    /** The films and shows [name] is in or directed, as the viewer's library has them. */
    public fun titlesOfPerson(playlistId: PlaylistId, name: String): Pair<List<MovieRow>, List<SeriesRow>> {
        val rows = detailQueries.titlesOfPerson(playlistId.value, name).executeAsList()
        val movieIds = rows.filter { it.content_type == ContentType.MOVIE.name }.map { it.content_id }.distinct()
        val seriesIds = rows.filter { it.content_type == ContentType.SERIES.name }.map { it.content_id }.distinct()
        return moviesByIds(playlistId, movieIds) to seriesByIds(playlistId, seriesIds)
    }

    private fun moviesByIds(playlistId: PlaylistId, ids: List<String>): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        return queries.moviesByIds(playlistId.value, snapshot, ids).executeAsList().map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite, it.quality, it.tags, it.language,
                it.version_count,
            )
        }
    }

    private fun seriesByIds(playlistId: PlaylistId, ids: List<String>): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        if (ids.isEmpty()) return emptyList()
        return queries.seriesByIds(playlistId.value, snapshot, ids).executeAsList().map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template, it.provider_series_id,
                it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    // ---- Shelves (Browse.sq) ----

    public fun topRatedMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return browseQueries.topRatedMovies(playlistId.value, snapshot, limit.toLong()).executeAsList().map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite, it.quality, it.tags, it.language,
                it.version_count,
            )
        }
    }

    public fun popularMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        val now = clock.now()
        // Recent films first; a library with too few of them is scored whole.
        val recent = browseQueries.popularMovies(
            playlistId.value,
            snapshot,
            (now - POPULAR_WINDOW).toEpochMilliseconds(),
            now.toEpochMilliseconds(),
            limit.toLong(),
        )
            .executeAsList()
        val rows = if (recent.size >=
            limit
        ) {
            recent
        } else {
            browseQueries.popularMovies(playlistId.value, snapshot, 0, now.toEpochMilliseconds(), limit.toLong()).executeAsList()
        }
        return rows.map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite, it.quality, it.tags, it.language,
                it.version_count,
            )
        }
    }

    public fun moviesOfGenre(playlistId: PlaylistId, genre: String, limit: Int, offset: Int = 0): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return browseQueries.moviesOfGenre(snapshot, playlistId.value, genre, limit.toLong(), offset.toLong()).executeAsList().map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite, it.quality, it.tags, it.language,
                it.version_count,
            )
        }
    }

    /** Genres with how many films are in each, largest first. */
    public fun movieGenres(playlistId: PlaylistId, limit: Int): List<Pair<String, Long>> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return browseQueries.movieGenres(snapshot, playlistId.value, limit.toLong()).executeAsList().map { it.genre to it.title_count }
    }

    public fun moviesOfDecade(playlistId: PlaylistId, decade: Int, limit: Int, offset: Int = 0): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        val (from, to) = decade.toLong() to (decade + 9).toLong()
        return browseQueries.moviesOfDecade(playlistId.value, snapshot, from, to, limit.toLong(), offset.toLong())
            .executeAsList().map {
                movieRow(
                    it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                    it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite, it.quality, it.tags, it.language,
                    it.version_count,
                )
            }
    }

    /** Decades with how many films, most recent first. */
    public fun movieDecades(playlistId: PlaylistId): List<Pair<Int, Long>> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return browseQueries.movieDecades(playlistId.value, snapshot).executeAsList().mapNotNull { row ->
            row.decade?.toInt()?.let { it to row.title_count }
        }
    }

    public fun movieVersions(playlistId: PlaylistId, id: String): List<MovieVersionRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return browseQueries.movieVersions(playlistId.value, snapshot, id).executeAsList()
            .map { MovieVersionRow(it.id, it.title, qualityOf(it.quality), decodeList(it.tags), it.language) }
    }

    public fun favoriteMovies(playlistId: PlaylistId, limit: Int): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return browseQueries.favoriteMovies(playlistId.value, snapshot, limit.toLong()).executeAsList().map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.added_at, it.position_ms, it.watched_duration_ms, it.completed, true, it.quality, it.tags, it.language, it.version_count,
            )
        }
    }

    public fun moviesInUserGroup(playlistId: PlaylistId, groupId: String, limit: Int): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return browseQueries.moviesInUserGroup(snapshot, playlistId.value, groupId, limit.toLong()).executeAsList().map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite, it.quality, it.tags, it.language,
                it.version_count,
            )
        }
    }

    public fun seriesInUserGroup(playlistId: PlaylistId, groupId: String, limit: Int): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return browseQueries.seriesInUserGroup(snapshot, playlistId.value, groupId, limit.toLong()).executeAsList().map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template, it.provider_series_id,
                it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    public fun recentlyWatchedMovieIds(playlistId: PlaylistId, limit: Int): List<String> =
        browseQueries.recentlyWatchedMovieIds(playlistId.value, limit.toLong()).executeAsList()

    public fun newEpisodeSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return browseQueries.newEpisodeSeries(playlistId.value, snapshot, limit.toLong()).executeAsList().map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template, it.provider_series_id,
                it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    public fun topRatedSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return browseQueries.topRatedSeries(playlistId.value, snapshot, limit.toLong()).executeAsList().map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template, it.provider_series_id,
                it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    public fun popularSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        val now = clock.now()
        val recent = browseQueries.popularSeries(
            playlistId.value,
            snapshot,
            (now - POPULAR_WINDOW).toEpochMilliseconds(),
            now.toEpochMilliseconds(),
            limit.toLong(),
        )
            .executeAsList()
        val rows = if (recent.size >=
            limit
        ) {
            recent
        } else {
            browseQueries.popularSeries(playlistId.value, snapshot, 0, now.toEpochMilliseconds(), limit.toLong()).executeAsList()
        }
        return rows.map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.provider_series_id,
                it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    public fun seriesOfGenre(playlistId: PlaylistId, genre: String, limit: Int, offset: Int = 0): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return browseQueries.seriesOfGenre(snapshot, playlistId.value, genre, limit.toLong(), offset.toLong()).executeAsList().map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template, it.provider_series_id,
                it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    public fun seriesGenres(playlistId: PlaylistId, limit: Int): List<Pair<String, Long>> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return browseQueries.seriesGenres(snapshot, playlistId.value, limit.toLong()).executeAsList().map { it.genre to it.title_count }
    }

    public fun favoriteSeries(playlistId: PlaylistId, limit: Int): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return browseQueries.favoriteSeries(playlistId.value, snapshot, limit.toLong()).executeAsList().map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template, it.provider_series_id,
                true, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    // --- Outside lists (ADR-0038) ---

    /** Replaces [list] with [entries], in their order. */
    public fun saveList(list: String, entries: List<ListEntry>) {
        val now = clock.now().toEpochMilliseconds()
        content.transaction {
            tmdbQueries.deleteList(list)
            entries.forEachIndexed { rank, e ->
                tmdbQueries.insertTitle(
                    list, rank.toLong(), e.tmdbId, e.type.name, e.title, e.year?.toLong(), e.rating, e.votes?.toLong(),
                    e.tmdbKey, e.titleKey, e.bareTitleKey, now,
                )
            }
        }
    }

    public fun listsFetchedAt(): Instant? = tmdbQueries.lastFetched().executeAsOneOrNull()?.MAX?.let { Instant.fromEpochMilliseconds(it) }

    public fun listCounts(): Map<String, Long> = tmdbQueries.listCounts().executeAsList().associate { it.list to it.title_count }

    public fun clearLists() {
        tmdbQueries.deleteAll()
        tmdbQueries.deleteArt()
    }

    /** TMDB artwork stored for a work (ADR-0039); null when it was never asked about. */
    public fun artOf(type: ContentType, workKey: String): StoredArt? = tmdbQueries.artOf(type.name, workKey).executeAsOneOrNull()?.let {
        StoredArt(it.tmdb_id, it.logo_path, Instant.fromEpochMilliseconds(it.fetched_at), it.backdrop_path)
    }

    /** Stores a work's artwork and the portraits of its people. */
    public fun saveArt(
        type: ContentType,
        workKey: String,
        tmdbId: Long?,
        logoPath: String?,
        portraits: List<Portrait>,
        backdropPath: String? = null,
    ) {
        val now = clock.now().toEpochMilliseconds()
        content.transaction {
            tmdbQueries.saveArt(type.name, workKey, tmdbId, logoPath, now, backdropPath)
            portraits.forEach { tmdbQueries.savePortrait(it.name, it.tmdbId, it.profilePath, now) }
        }
    }

    public fun personOf(name: String): StoredPerson? = tmdbQueries.personOf(name).executeAsOneOrNull()?.let {
        StoredPerson(it.tmdb_id, it.profile_path, it.biography, Instant.fromEpochMilliseconds(it.fetched_at))
    }

    public fun savePerson(name: String, tmdbId: Long?, profilePath: String?, biography: String?) {
        tmdbQueries.savePerson(name, tmdbId, profilePath, biography, clock.now().toEpochMilliseconds())
    }

    /** Portrait paths of [names], for those TMDB has one of. */
    public fun portraitsOf(names: Collection<String>): Map<String, String> =
        if (names.isEmpty()) emptyMap() else tmdbQueries.portraitsOf(names).executeAsList().associate { it.name to it.profile_path }

    /** The films of [list] the library holds, in the list's order. */
    public fun moviesOfList(playlistId: PlaylistId, list: String, limit: Int): List<MovieRow> {
        val snapshot = active(playlistId, ImportUnit.MOVIES) ?: return emptyList()
        return tmdbQueries.moviesOfList(playlistId.value, snapshot, list, limit.toLong()).executeAsList().map {
            movieRow(
                it.id, it.title, it.year, it.duration_seconds, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template,
                it.added_at, it.position_ms, it.watched_duration_ms, it.completed, it.is_favorite, it.quality, it.tags, it.language,
                it.version_count,
            )
        }
    }

    public fun seriesOfList(playlistId: PlaylistId, list: String, limit: Int): List<SeriesRow> {
        val snapshot = active(playlistId, ImportUnit.SERIES) ?: return emptyList()
        return tmdbQueries.seriesOfList(playlistId.value, snapshot, list, limit.toLong()).executeAsList().map {
            seriesRow(
                it.id, it.title, it.year, it.plot, it.genres, it.rating, it.poster_template, it.backdrop_template, it.provider_series_id,
                it.is_favorite, it.quality, it.tags, it.language, it.last_modified_at,
            )
        }
    }

    /** TMDB's rating of the work with [workKey], when a stored list mentions it. */
    public fun listRating(type: ContentType, workKey: String): Double? = tmdbQueries.ratingOf(type.name, workKey).executeAsOneOrNull()?.MAX

    public fun recordChannelWatch(playlistId: PlaylistId, channelId: String) {
        detailQueries.recordChannelWatch(playlistId.value, channelId, clock.now().toEpochMilliseconds())
    }

    public fun mostWatchedChannelIds(playlistId: PlaylistId, limit: Int): List<String> =
        detailQueries.mostWatchedChannels(playlistId.value, limit.toLong()).executeAsList()

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
                    movie.addedAt?.toEpochMilliseconds(), movie.quality?.name, encodeList(movie.tags), movie.language,
                    movie.externalIds.tmdb, movie.workKey, ratingValue(movie.rating),
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
                    series.lastModifiedAt?.toEpochMilliseconds(), series.quality?.name, encodeList(series.tags), series.language,
                    series.externalIds.tmdb, ratingValue(series.rating), series.workKey,
                )
                // A show's list entry already carries its page (genres, cast, trailer), so it is kept without a second request.
                val detail = (series.detail ?: TitleDetail()).copy(
                    plot = series.detail?.plot ?: series.plot,
                    genres = series.detail?.genres?.ifEmpty { null } ?: series.genres,
                    rating = series.detail?.rating ?: series.rating,
                )
                if (!detail.isEmpty) writeDetail(playlistId, ContentType.SERIES, series.id.value, detail)
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
                if (unit == ImportUnit.MOVIES) {
                    queries.markPrimaryVersions(playlistId.value, snapshot)
                    queries.applyPages(playlistId.value, snapshot)
                }
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
        const val ROLE_CAST = "cast"
        const val ROLE_DIRECTOR = "director"

        /** How many person rows a search reads before grouping them by name. */
        const val PEOPLE_CANDIDATES = 400L

        /** "Popular" scores films added within this window; older ones would lose two points a year and never place. */
        val POPULAR_WINDOW = (2 * 365).days

        /** A provider's rating as a number from 0 to 10, or null when it is missing, zero or not a rating. */
        fun ratingValue(rating: String?): Double? = rating?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 && it <= 10 }
        const val COMPLETED_FRACTION = 0.95

        /** SQLite reports SUM as a real number; the coverage counts are whole. */
        fun Double?.count(): Long = this?.toLong() ?: 0L
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
            quality: String? = null,
            tags: String? = null,
            language: String? = null,
            versions: Long? = null,
        ) = MovieRow(
            id, title, year?.toInt(), duration?.seconds, plot, decodeList(genres), rating, poster?.let(::UrlTemplate),
            backdrop?.let(::UrlTemplate),
            added?.let(Instant::fromEpochMilliseconds), progressOf(position, watched ?: duration?.times(1000), completed), favorite == true,
            qualityOf(quality), decodeList(tags), language, versions?.toInt() ?: 1,
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
            quality: String? = null,
            tags: String? = null,
            language: String? = null,
            modified: Long? = null,
        ) = SeriesRow(
            id, title, year?.toInt(), plot, decodeList(genres), rating, poster?.let(::UrlTemplate), backdrop?.let(::UrlTemplate), provider,
            favorite == true, qualityOf(quality), decodeList(tags), language, modified?.let(Instant::fromEpochMilliseconds),
        )

        fun qualityOf(name: String?): Quality? = name?.let { value -> Quality.entries.firstOrNull { it.name == value } }

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
