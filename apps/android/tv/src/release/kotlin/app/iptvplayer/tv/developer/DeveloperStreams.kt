package app.iptvplayer.tv.developer

import android.content.Context

/** Release build: no developer streams and no test server. */
object DeveloperStreams {
    @Suppress("UNUSED_PARAMETER")
    fun list(context: Context): List<DeveloperStream> = emptyList()
}
