package app.iptvplayer.domain.model

import app.iptvplayer.domain.id.ArtworkId
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpisodeId
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.MovieId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.domain.id.SeasonId
import app.iptvplayer.domain.id.SeriesId
import app.iptvplayer.domain.library.Quality
import app.iptvplayer.domain.library.TitleCleaner
import app.iptvplayer.domain.security.UrlTemplate
import kotlin.time.Duration
import kotlin.time.Instant

/** Upper bounds enforced during NORMALIZE (docs/DOMAIN_MODEL.md §7). */
public object DomainLimits {
    public const val MAX_NAME_LENGTH: Int = 512
    public const val MAX_DESCRIPTION_LENGTH: Int = 16 * 1024
    public const val MAX_EXTRAS: Int = 32
    public const val MAX_EXTRA_VALUE_LENGTH: Int = 1024
}

public enum class ContentKind { LIVE, MOVIE, SERIES }

public data class ChannelGroup(
    public val id: GroupId,
    public val playlistId: PlaylistId,
    public val contentKind: ContentKind,
    /** Provider title, whitespace-normalized only. User overrides live in user-state tables. */
    public val title: String,
    public val providerSortOrder: Int,
    public val providerCategoryId: String?,
)

public enum class CatchUpMode { XTREAM_TIMESHIFT, M3U_DEFAULT, M3U_APPEND, M3U_SHIFT, M3U_FLUSSONIC, M3U_TEMPLATE }

public data class CatchUpInfo(public val mode: CatchUpMode, public val days: Int?, public val template: UrlTemplate?)

/** Hints used by identity carry-over after refresh (§4.4). Not shown in UI. */
public data class IdentityHints(public val urlFingerprint: String?, public val tvgId: String?, public val matchName: String)

public data class Channel(
    public val id: ChannelId,
    public val playlistId: PlaylistId,
    public val groupIds: List<GroupId>,
    public val name: String,
    public val number: Int?,
    public val logo: ArtworkId?,
    public val tvgId: String?,
    public val providerStreamId: String?,
    public val languages: List<String>,
    public val countries: List<String>,
    public val catchUp: CatchUpInfo?,
    public val mediaSourceIds: List<MediaSourceId>,
    public val isAdult: Boolean?,
    /** Unknown provider attributes, preserved for forward compatibility. */
    public val extras: Map<String, String>,
    public val identityHints: IdentityHints,
) {
    init {
        require(name.isNotBlank()) { "channel name must not be blank" }
        require(name.length <= DomainLimits.MAX_NAME_LENGTH) { "channel name too long" }
        require(mediaSourceIds.isNotEmpty()) { "channel needs at least one media source" }
        require(extras.size <= DomainLimits.MAX_EXTRAS) { "too many extras" }
    }
}

public data class DisplayName(public val text: String, public val language: String?)

public data class EpgChannel(public val key: EpgChannelKey, public val displayNames: List<DisplayName>, public val icon: ArtworkId?)

public enum class EpgMatchMethod { USER_OVERRIDE, TVG_ID, XTREAM_EPG_ID, NAME_EXACT, NAME_NORMALIZED }

public data class ChannelEpgLink(
    public val channelId: ChannelId,
    public val epgChannelKey: EpgChannelKey,
    public val method: EpgMatchMethod,
    public val confidence: Int,
    public val epgSourcePriority: Int,
) {
    init {
        require(confidence in 0..100) { "confidence must be in 0..100" }
    }
}

public data class EpisodeNumbering(public val season: Int?, public val episode: Int?, public val part: Int?, public val onScreen: String?)

public data class ContentRating(public val system: String?, public val value: String)

public data class ProgramFlags(
    public val isNew: Boolean = false,
    public val isLive: Boolean = false,
    public val isPremiere: Boolean = false,
    public val previouslyShown: Boolean = false,
)

public data class Program(
    public val id: ProgramId,
    public val epgChannelKey: EpgChannelKey,
    public val start: Instant,
    public val end: Instant,
    public val title: String,
    public val subtitle: String?,
    public val description: String?,
    public val categories: List<String>,
    public val episode: EpisodeNumbering?,
    public val artwork: List<ArtworkId>,
    public val rating: ContentRating?,
    public val flags: ProgramFlags,
    public val language: String?,
) {
    init {
        require(end > start) { "programme end must be after start" }
        require(title.isNotBlank()) { "programme title must not be blank" }
        require((description?.length ?: 0) <= DomainLimits.MAX_DESCRIPTION_LENGTH) { "description too long" }
    }

    public val duration: Duration get() = end - start
}

public data class ExternalIds(public val tmdb: String? = null, public val imdb: String? = null)

public data class Movie(
    public val id: MovieId,
    public val playlistId: PlaylistId,
    public val groupIds: List<GroupId>,
    public val title: String,
    public val year: Int?,
    public val duration: Duration?,
    public val plot: String?,
    public val genres: List<String>,
    public val rating: String?,
    public val releaseDate: String?,
    public val poster: ArtworkId?,
    public val backdrop: ArtworkId?,
    public val mediaSourceIds: List<MediaSourceId>,
    public val providerStreamId: String?,
    public val externalIds: ExternalIds,
    public val addedAt: Instant?,
    /** Picture quality, badges and language taken out of the provider's title (TitleCleaner). */
    public val quality: Quality? = null,
    public val tags: List<String> = emptyList(),
    public val language: String? = null,
) {
    /** The film itself, so versions of it can be shown as one card (TitleCleaner.workKey). */
    public val workKey: String get() = externalIds.tmdb?.let { "tmdb:$it" } ?: TitleCleaner.workKey(title, year)

    init {
        require(title.isNotBlank()) { "movie title must not be blank" }
        require(mediaSourceIds.isNotEmpty()) { "movie needs at least one media source" }
    }
}

public data class Series(
    public val id: SeriesId,
    public val playlistId: PlaylistId,
    public val groupIds: List<GroupId>,
    public val title: String,
    public val year: Int?,
    public val plot: String?,
    public val genres: List<String>,
    public val rating: String?,
    public val poster: ArtworkId?,
    public val backdrop: ArtworkId?,
    public val providerSeriesId: String?,
    public val externalIds: ExternalIds,
    public val lastModifiedAt: Instant?,
    public val quality: Quality? = null,
    public val tags: List<String> = emptyList(),
    public val language: String? = null,
    /** What the provider's series list already says about the show beyond its title (cast, director, trailer). */
    public val detail: TitleDetail? = null,
) {
    /** The show itself, for matching it in outside lists (ADR-0038); built like [Movie.workKey]. */
    public val workKey: String get() = externalIds.tmdb?.let { "tmdb:$it" } ?: TitleCleaner.workKey(title, year)

    init {
        require(title.isNotBlank()) { "series title must not be blank" }
    }
}

public data class Season(
    public val id: SeasonId,
    public val seriesId: SeriesId,
    public val seasonNumber: Int,
    public val title: String?,
    public val poster: ArtworkId?,
    public val episodeCount: Int?,
)

public data class Episode(
    public val id: EpisodeId,
    public val seasonId: SeasonId,
    public val seriesId: SeriesId,
    public val episodeNumber: Int,
    public val title: String?,
    public val plot: String?,
    public val duration: Duration?,
    public val airDate: String?,
    public val still: ArtworkId?,
    public val mediaSourceIds: List<MediaSourceId>,
    public val providerEpisodeId: String?,
) {
    init {
        require(mediaSourceIds.isNotEmpty()) { "episode needs at least one media source" }
    }
}

/**
 * What a provider says about a film or show beyond its list entry (Xtream `get_vod_info`, or the series list itself):
 * the page a viewer opens. Kept apart from the imported rows so a refresh does not throw it away, and every field is
 * optional because providers fill them unevenly.
 */
public data class TitleDetail(
    public val plot: String? = null,
    public val genres: List<String> = emptyList(),
    public val duration: kotlin.time.Duration? = null,
    public val releaseDate: String? = null,
    public val year: Int? = null,
    public val poster: UrlTemplate? = null,
    public val backdrop: UrlTemplate? = null,
    public val cast: List<String> = emptyList(),
    public val directors: List<String> = emptyList(),
    /** A YouTube video id or link, as the provider wrote it. */
    public val trailer: String? = null,
    public val country: String? = null,
    public val ageRating: String? = null,
    public val rating: String? = null,
    public val tmdbId: String? = null,
) {
    /** True when the provider sent nothing worth keeping. */
    public val isEmpty: Boolean
        get() = plot == null && genres.isEmpty() && duration == null && releaseDate == null && year == null && poster == null &&
            backdrop == null && cast.isEmpty() && directors.isEmpty() && trailer == null && country == null && ageRating == null &&
            rating == null && tmdbId == null
}

public enum class ArtworkKind { LOGO, POSTER, BACKDROP, THUMBNAIL, PROGRAM_ICON, SEASON_POSTER }

public enum class ArtworkOrigin { PROVIDER, XMLTV, ENRICHMENT, USER }

public data class Artwork(
    public val id: ArtworkId,
    public val url: UrlTemplate,
    public val kind: ArtworkKind,
    public val origin: ArtworkOrigin,
    public val widthHint: Int?,
    public val heightHint: Int?,
)
