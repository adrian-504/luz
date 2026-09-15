package app.iptvplayer.tv.app

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.iptvplayer.tv.developer.DeveloperStreams

class IptvApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: the synthetic test provider's in-process server (no-op in release builds).
        DeveloperStreams.start(this)
    }
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
