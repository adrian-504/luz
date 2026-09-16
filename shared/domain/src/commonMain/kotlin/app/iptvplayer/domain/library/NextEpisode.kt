package app.iptvplayer.domain.library

/**
 * Which episode "Continue" plays (REQUIREMENTS.md FR-SER-001), shared by all platforms. [episodes] are in viewing order
 * (season, then episode); [state] returns the watch state of an episode or null when never played.
 */
public object NextEpisode {
    public enum class Watch { IN_PROGRESS, COMPLETED }

    public data class Pick<T>(public val episode: T, public val resume: Boolean)

    /**
     * The most recently played episode when it is unfinished (resume it); after a finished one, the next episode in order;
     * the first episode when nothing was played. Null when the list is empty or the last episode was finished.
     */
    public fun <T> pick(episodes: List<T>, lastPlayed: T?, state: (T) -> Watch?): Pick<T>? {
        if (episodes.isEmpty()) return null
        val index = lastPlayed?.let { episodes.indexOf(it) } ?: -1
        if (index < 0) return Pick(episodes.first(), resume = false)
        return when (state(episodes[index])) {
            Watch.IN_PROGRESS -> Pick(episodes[index], resume = true)
            Watch.COMPLETED -> episodes.getOrNull(index + 1)?.let { Pick(it, resume = false) }
            null -> Pick(episodes[index], resume = false)
        }
    }

    /** The episode after [current] in viewing order, for "Next episode" at the end of playback. */
    public fun <T> after(episodes: List<T>, current: T): T? =
        episodes.indexOf(current).takeIf { it >= 0 }?.let { episodes.getOrNull(it + 1) }
}
