package app.iptvplayer.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.AppNavHost
import app.iptvplayer.tv.ui.theme.IptvTheme
import app.iptvplayer.tv.ui.theme.OkKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as IptvApplication).graph
        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                IptvTheme {
                    AppNavHost()
                }
            }
        }
    }

    /** Notes whether OK is held, for menus that open under a held OK (LuzMenu). Changes nothing about the event. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        OkKey.observe(event)
        return super.dispatchKeyEvent(event)
    }
}
