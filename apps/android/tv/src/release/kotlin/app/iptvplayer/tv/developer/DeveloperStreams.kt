package app.iptvplayer.tv.developer

import android.content.Context

/** Release build: no developer streams and no test server. */
object DeveloperStreams {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun list(context: Context): List<DeveloperStream> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun testProvider(context: Context): Triple<String, String, String>? = null

    @Suppress("UNUSED_PARAMETER")
    fun tmdbBase(context: Context): String? = null
}
