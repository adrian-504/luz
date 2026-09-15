package app.iptvplayer.tv

import androidx.test.platform.app.InstrumentationRegistry
import app.iptvplayer.tv.app.IptvApplication
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Removes every configured source before the activity starts, so each test begins at Welcome with an empty library. */
class NoSourcesRule : ExternalResource() {
    override fun before() {
        clear()
    }

    override fun after() {
        clear()
    }

    private fun clear() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IptvApplication
        runBlocking { app.graph.sources().forEach { app.graph.delete(it.playlistId) } }
    }
}
