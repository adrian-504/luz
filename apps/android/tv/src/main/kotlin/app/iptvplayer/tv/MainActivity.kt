package app.iptvplayer.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.ui.AppNavHost
import app.iptvplayer.tv.ui.theme.IptvTheme

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
}
