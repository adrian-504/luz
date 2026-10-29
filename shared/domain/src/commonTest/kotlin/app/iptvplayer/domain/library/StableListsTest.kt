package app.iptvplayer.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StableListsTest {
    @Test
    fun focusStaysOnTheSameKeyWhenItSurvives() {
        assertEquals("c", StableLists.keepNear(listOf("a", "b", "c", "d"), listOf("x", "a", "c", "d"), "c"))
    }

    @Test
    fun focusMovesToTheSamePositionWhenItsItemIsGone() {
        assertEquals("d", StableLists.keepNear(listOf("a", "b", "c", "d"), listOf("a", "b", "d"), "c"))
    }

    @Test
    fun focusMovesToTheLastItemWhenTheListShrankPastIt() {
        assertEquals("b", StableLists.keepNear(listOf("a", "b", "c", "d"), listOf("a", "b"), "d"))
    }

    @Test
    fun anEmptyListGivesNoTarget() {
        assertNull(StableLists.keepNear(listOf("a"), emptyList(), "a"))
    }
}
