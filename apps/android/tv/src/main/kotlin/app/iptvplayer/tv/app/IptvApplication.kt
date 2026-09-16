package app.iptvplayer.tv.app

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.iptvplayer.tv.developer.DeveloperStreams
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okio.Path.Companion.toOkioPath

class IptvApplication :
    Application(),
    SingletonImageLoader.Factory {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: the synthetic test provider's in-process server (no-op in release builds).
        DeveloperStreams.start(this)
    }

    /**
     * Posters and backdrops (ADR-0028): a bounded memory cache for TVs with little RAM, a disk cache for repeat visits, and
     * no crossfade so focus moves stay fast on slow devices. Coil logs nothing unless a logger is set.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(200L * 1024 * 1024).build() }
        .components { add(OkHttpNetworkFetcherFactory()) }
        .build()
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
