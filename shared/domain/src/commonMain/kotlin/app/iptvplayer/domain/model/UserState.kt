package app.iptvplayer.domain.model

import app.iptvplayer.domain.id.FavoriteId
import app.iptvplayer.domain.id.PlaylistId
import kotlin.time.Duration
import kotlin.time.Instant

/** User state is stored separately from imported content and is never deleted by imports. */
public data class Favorite(
    public val id: FavoriteId,
    public val contentRef: ContentRef,
    public val sortOrder: Int,
    public val createdAt: Instant,
)

public data class WatchState(
    public val contentRef: ContentRef,
    public val playlistId: PlaylistId,
    /** Null for live channels, which only track recency. */
    public val position: Duration?,
    public val duration: Duration?,
    public val completed: Boolean,
    public val lastPlayedAt: Instant,
    public val playCount: Int,
    public val lastAudioLanguage: String?,
    public val lastSubtitleLanguage: String?,
) {
    init {
        require(position == null || !position.isNegative()) { "position must not be negative" }
        require(playCount >= 0) { "playCount must not be negative" }
    }

    public companion object {
        /** Share of the duration after which an item counts as watched. */
        public const val COMPLETION_THRESHOLD: Double = 0.95

        public fun isComplete(position: Duration, duration: Duration): Boolean =
            duration.isPositive() && position / duration >= COMPLETION_THRESHOLD
    }
}
