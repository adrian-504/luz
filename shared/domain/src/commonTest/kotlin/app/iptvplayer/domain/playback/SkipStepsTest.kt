package app.iptvplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Hold-to-skip (x16): the step grows with how long the key is held, whatever the box's repeat rate. */
class SkipStepsTest {
    @Test
    fun stepsGrowWithHeldTime() {
        assertEquals(10.seconds, SkipSteps.step(0))
        assertEquals(10.seconds, SkipSteps.step(1_499))
        assertEquals(30.seconds, SkipSteps.step(1_500))
        assertEquals(30.seconds, SkipSteps.step(2_999))
        assertEquals(1.minutes, SkipSteps.step(3_000))
        assertEquals(1.minutes, SkipSteps.step(60_000))
    }
}
