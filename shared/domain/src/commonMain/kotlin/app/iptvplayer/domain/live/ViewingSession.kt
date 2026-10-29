package app.iptvplayer.domain.live

/**
 * Counts one session's playing time for the viewing log (round 3, for "Year in Luz" in round 8): pauses and buffering
 * are left out, and a session under a minute is not logged. Times are milliseconds from any one clock.
 */
public class ViewingSession(public val startedAt: Long, private val minSeconds: Long = MIN_SECONDS) {
    private var playingSince: Long? = null
    private var counted = 0L

    /** Playing (again) from [at]. */
    public fun play(at: Long) {
        if (playingSince == null) playingSince = at
    }

    /** Paused, buffering or stopped at [at]. */
    public fun hold(at: Long) {
        playingSince?.let { counted += (at - it).coerceAtLeast(0) }
        playingSince = null
    }

    /** The session ends at [at] (left the player, changed channel, the next episode began, the screen went off). */
    public fun end(at: Long): Long? {
        hold(at)
        val seconds = counted / MS_PER_SECOND
        // Ended once: anything after belongs to the next session.
        counted = 0
        return seconds.takeIf { it >= minSeconds }
    }

    public companion object {
        public const val MIN_SECONDS: Long = 60
        private const val MS_PER_SECOND = 1000L
    }
}
