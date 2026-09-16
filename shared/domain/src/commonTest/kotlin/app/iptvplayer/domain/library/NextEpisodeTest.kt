package app.iptvplayer.domain.library

import app.iptvplayer.domain.library.NextEpisode.Watch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NextEpisodeTest {
    private val episodes = listOf("s1e1", "s1e2", "s2e1")

    @Test
    fun startsAtTheFirstEpisodeWhenNothingWasWatched() {
        assertEquals(NextEpisode.Pick("s1e1", resume = false), NextEpisode.pick(episodes, null) { null })
        assertEquals(NextEpisode.Pick("s1e1", resume = false), NextEpisode.pick(episodes, "gone") { null }, "a removed episode")
        assertNull(NextEpisode.pick(emptyList<String>(), null) { null })
    }

    @Test
    fun resumesAnUnfinishedEpisodeAndMovesOnAfterAFinishedOne() {
        assertEquals(NextEpisode.Pick("s1e2", resume = true), NextEpisode.pick(episodes, "s1e2") { Watch.IN_PROGRESS })
        assertEquals(NextEpisode.Pick("s2e1", resume = false), NextEpisode.pick(episodes, "s1e2") { Watch.COMPLETED }, "across seasons")
        assertNull(NextEpisode.pick(episodes, "s2e1") { Watch.COMPLETED }, "the series is finished")
    }

    @Test
    fun episodeAfterTheCurrentOne() {
        assertEquals("s1e2", NextEpisode.after(episodes, "s1e1"))
        assertNull(NextEpisode.after(episodes, "s2e1"))
        assertNull(NextEpisode.after(episodes, "missing"))
    }
}
