package app.iptvplayer.domain.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How far one skip goes while Left or Right is held in a film (x16): 10 s steps for the first 1.5 s, 30 s steps until 3 s,
 * then a minute at a time. Measured by how long the key has been held, not by how many repeats the box sends, so every
 * television skips at the same pace.
 */
public object SkipSteps {
    public val FIRST: Duration = 10.seconds
    public val FASTER: Duration = 30.seconds
    public val FASTEST: Duration = 1.minutes

    public fun step(heldMs: Long): Duration = when {
        heldMs >= FASTEST_AFTER_MS -> FASTEST
        heldMs >= FASTER_AFTER_MS -> FASTER
        else -> FIRST
    }

    private const val FASTER_AFTER_MS = 1_500L
    private const val FASTEST_AFTER_MS = 3_000L
}
