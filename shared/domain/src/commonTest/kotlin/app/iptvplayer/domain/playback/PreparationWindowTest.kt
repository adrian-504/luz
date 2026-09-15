package app.iptvplayer.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PreparationWindowTest {
    private val channels = listOf("a", "b", "c", "d", "e")

    @Test
    fun nextInTheZapDirectionComesFirstThenTheOtherWayThenTheLastChannel() {
        assertEquals(listOf("d", "b", "a"), PreparationWindow.candidates(channels, 2, +1, lastChannel = "a"))
        assertEquals(listOf("b", "d", "a"), PreparationWindow.candidates(channels, 2, -1, lastChannel = "a"))
        assertEquals(listOf("d", "b"), PreparationWindow.candidates(channels, 2, 0, lastChannel = null))
    }

    @Test
    fun theListWrapsAroundAndSkipsTheCurrentChannelAndDuplicates() {
        assertEquals(listOf("a", "d"), PreparationWindow.candidates(channels, 4, +1, lastChannel = "a"))
        assertEquals(listOf("b"), PreparationWindow.candidates(listOf("a", "b"), 0, +1, lastChannel = "b"))
        assertEquals(emptyList(), PreparationWindow.candidates(listOf("a"), 0, +1, lastChannel = "a"))
        assertEquals(emptyList(), PreparationWindow.candidates(emptyList<String>(), 0, +1, lastChannel = null))
        assertEquals(emptyList(), PreparationWindow.candidates(channels, 9, +1, lastChannel = null))
    }

    @Test
    fun neverMoreThanThreeCandidates() {
        val result = PreparationWindow.candidates(channels, 0, +1, lastChannel = "c")
        assertEquals(listOf("b", "e", "c"), result)
        assertEquals(PreparationWindow.MAX_T0_CANDIDATES, result.size)
    }

    @Test
    fun lastChannelIsThePreviouslyPlayedChannel() {
        val history = ChannelHistory<String>()
        assertNull(history.last)
        history.played("a")
        assertNull(history.last)
        history.played("b")
        history.played("b")
        assertEquals("a", history.last)
        history.played("a")
        assertEquals("b", history.last)
        assertEquals("a", history.current)
    }
}
