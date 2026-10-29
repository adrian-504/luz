package app.iptvplayer.tv

import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.app.REMINDER_LEAD_DEFAULT
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/**
 * Removes every configured source before the activity starts, so each test begins at Welcome with an empty library; and
 * what is followed in sport and the sport key, which are not per source (ADR-0057) and would otherwise carry over.
 */
class NoSourcesRule : ExternalResource() {
    override fun before() {
        clear()
    }

    override fun after() {
        clear()
    }

    private fun clear() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IptvApplication
        runBlocking {
            app.graph.sources().forEach { app.graph.delete(it.playlistId) }
            app.graph.follows().forEach { app.graph.unfollow(it.kind, it.ref) }
            app.graph.removeSportToken()
        }
        app.graph.reminderLead = REMINDER_LEAD_DEFAULT
    }
}
