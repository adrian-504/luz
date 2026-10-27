package app.iptvplayer.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
                    // Notes whether OK is held, for menus that open under a held OK (LuzMenu); passes every key on.
                    // Test tags double as resource ids, so the robot in apps/android/benchmark can find its way (ADR-0049).
                    Box(Modifier.onPreviewKeyEvent { OkKey.observe(it.nativeKeyEvent) }.semantics { testTagsAsResourceId = true }) {
                        AppNavHost()
                    }
                }
            }
        }
    }
}
