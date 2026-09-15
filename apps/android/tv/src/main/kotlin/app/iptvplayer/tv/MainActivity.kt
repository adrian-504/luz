package app.iptvplayer.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.iptvplayer.tv.ui.AppNavHost
import app.iptvplayer.tv.ui.theme.IptvTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IptvTheme {
                AppNavHost()
            }
        }
    }
}
