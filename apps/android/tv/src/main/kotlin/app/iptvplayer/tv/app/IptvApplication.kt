package app.iptvplayer.tv.app

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import app.iptvplayer.tv.BuildConfig
import app.iptvplayer.tv.developer.DeveloperStreams
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import coil3.util.DebugLogger
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import kotlin.concurrent.thread

class IptvApplication :
    Application(),
    SingletonImageLoader.Factory {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: the synthetic test provider's in-process server (no-op in release builds).
        DeveloperStreams.start(this)
        // The phone companion, if the viewer left it on; off the main thread, so it never delays the first screen.
        thread(name = "companion-start", isDaemon = true) { graph.companion.resume() }
    }

    /**
     * Gives pictures back when Android asks (ADR-0051): all of them once Luz is in the background or memory is critical,
     * half once Luz has left the screen or memory runs low. The levels overlap as numbers, so the rule is written out.
     */
    @Suppress("DEPRECATION") // The level constants are deprecated from API 34 but still sent, and the Bbox runs API 30.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val cache = SingletonImageLoader.get(this).memoryCache ?: return
        when {
            level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL -> cache.clear()
            level >= TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(cache.maxSize / 2)
        }
    }

    /**
     * Posters and backdrops (ADR-0028): a bounded memory cache for TVs with little RAM, a disk cache for repeat visits.
     * A picture fades in only when it is first decoded (ADR-0051, set per request), never on a focus move. Coil logs
     * nothing unless a logger is set.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork").toOkioPath()).maxSizeBytes(200L * 1024 * 1024).build() }
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { artworkHttpClient() }))
            // About one channel logo in twenty is an SVG (Wikimedia's especially); without this they never showed.
            add(SvgDecoder.Factory())
        }
        // Debug builds say why a picture did not appear; release builds stay silent (artwork URLs can carry a login).
        .apply { if (BuildConfig.DEBUG) logger(DebugLogger()) }
        .build()
}

/**
 * The client artwork is fetched with. It says who is asking: Wikimedia — where about a third of the owner's channel
 * logos live — answers 403 to requests that carry a library's default name, so those logos never arrived. Nothing else
 * is added to the request: an artwork address can carry a provider login, and it is sent as it is stored.
 */
private fun artworkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", ARTWORK_USER_AGENT).build())
    }
    .build()

private val ARTWORK_USER_AGENT = "Luz/${BuildConfig.VERSION_NAME} (+https://github.com/adrian-504/luz)"

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
