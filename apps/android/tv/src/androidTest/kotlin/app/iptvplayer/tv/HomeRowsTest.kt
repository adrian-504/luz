package app.iptvplayer.tv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.tv.ui.library.HOME_ROW_TITLES
import app.iptvplayer.tv.ui.library.HomeTags
import app.iptvplayer.tv.ui.library.greeting
import app.iptvplayer.tv.ui.library.timeLeft
import app.iptvplayer.tv.ui.library.withNewRows
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.minutes

/** Home's rows as the viewer chose them, with rows Luz gained since; the greeting and the time left on a card. */
@RunWith(AndroidJUnit4::class)
class HomeRowsTest {
    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun rowsAddedSinceTheViewerSavedTheirChoiceAppearWhereTheyBelong() {
        val saved = listOf(HomeTags.CONTINUE, HomeTags.CHANNELS, HomeTags.MOVIES, HomeTags.BECAUSE)
        // Saved before Luz noted which rows existed: the two new rows come in after the rows they follow.
        assertEquals(
            listOf(HomeTags.CONTINUE, HomeTags.CHANNELS, HomeTags.COMING_UP, HomeTags.MOVIES, HomeTags.BECAUSE, HomeTags.GENRE),
            withNewRows(saved, known = null),
        )
        // Saved knowing every row: a row left out stays out.
        assertEquals(saved, withNewRows(saved, known = HOME_ROW_TITLES.map { it.first }))
    }

    @Test
    fun theGreetingFollowsTheHourAndTheTimeLeftIsShort() {
        assertEquals("Good morning", greeting(resources, 8))
        assertEquals("Good afternoon", greeting(resources, 14))
        assertEquals("Good evening", greeting(resources, 23))
        assertEquals("Good evening", greeting(resources, 2))
        assertEquals("32 min left", timeLeft(resources, 32.minutes))
        assertEquals("1 h 5 min left", timeLeft(resources, 65.minutes))
    }
}
