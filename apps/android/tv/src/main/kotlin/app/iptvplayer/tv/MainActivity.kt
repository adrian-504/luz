package app.iptvplayer.tv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.iptvplayer.tv.app.IptvApplication
import app.iptvplayer.tv.app.LocalAppGraph
import app.iptvplayer.tv.companion.CompanionHub
import app.iptvplayer.tv.ui.AppNavHost
import app.iptvplayer.tv.ui.theme.IptvTheme
import app.iptvplayer.tv.ui.theme.OkKey
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as IptvApplication).graph
        // Keys pressed on the phone companion's pad (ADR-0050) play into Luz as if from the remote, and nowhere else.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CompanionHub.keys.collect { code ->
                    window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
            }
        }
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

    override fun onStart() {
        super.onStart()
        CompanionHub.setOnScreen(true)
    }

    override fun onStop() {
        CompanionHub.setOnScreen(false)
        super.onStop()
    }
}
