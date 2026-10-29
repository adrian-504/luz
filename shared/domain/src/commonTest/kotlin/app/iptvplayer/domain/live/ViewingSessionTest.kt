package app.iptvplayer.domain.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ViewingSessionTest {
    @Test
    fun pausesAreNotCounted() {
        val session = ViewingSession(0)
        session.play(0)
        session.hold(60_000)
        session.play(600_000)
        assertEquals(90, session.end(630_000))
    }

    @Test
    fun underAMinuteIsNotLogged() {
        val session = ViewingSession(0)
        session.play(0)
        assertNull(session.end(59_000))
    }

    @Test
    fun aChannelChangeEndsTheSession() {
        val first = ViewingSession(0)
        first.play(0)
        assertEquals(120, first.end(120_000))
        assertNull(first.end(500_000), "an ended session adds nothing more")
    }
}
