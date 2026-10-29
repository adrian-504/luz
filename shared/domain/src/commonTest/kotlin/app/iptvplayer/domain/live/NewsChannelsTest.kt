package app.iptvplayer.domain.live

import app.iptvplayer.domain.live.NewsChannels.Candidate
import app.iptvplayer.domain.live.NewsChannels.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewsChannelsTest {
    @Test
    fun arabicGroupWordFindsNews() {
        val found = NewsChannels.classify(listOf(Candidate("1", "Al Jadeed", "أخبار لبنان")))
        assertEquals(listOf("1"), found[Language.ARABIC])
    }

    @Test
    fun frenchInfoChannelIsFrench() {
        val found = NewsChannels.classify(listOf(Candidate("2", "FR| Test Info", "World News")))
        assertEquals(listOf("2"), found[Language.FRENCH])
    }

    @Test
    fun infoInsideAWordIsNotNews() {
        assertTrue(NewsChannels.classify(listOf(Candidate("3", "UK| Infinity", "Entertainment"))).isEmpty())
    }

    @Test
    fun unknownLanguageIsLeftOut() {
        assertTrue(NewsChannels.classify(listOf(Candidate("4", "World News 24", "News"))).isEmpty())
    }

    @Test
    fun mostWatchedComeFirst() {
        val found = NewsChannels.classify(
            listOf(Candidate("a", "UK| Sky News", null, watched = 1), Candidate("b", "UK| BBC News", null, watched = 5)),
        )
        assertEquals(listOf("b", "a"), found[Language.ENGLISH])
    }
}
