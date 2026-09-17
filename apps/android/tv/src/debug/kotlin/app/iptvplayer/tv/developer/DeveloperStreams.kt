package app.iptvplayer.tv.developer

import android.content.Context
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.protocols.media.ResolvedMediaSource
import app.iptvplayer.testing.TestMediaServer
import app.iptvplayer.testing.TestPanel

/** Debug build: synthetic streams from an in-process [TestMediaServer] (tooling/fixtures/media). */
object DeveloperStreams {
    private const val FIXED_PORT = 18_080

    @Volatile
    private var server: TestMediaServer? = null

    private fun server(context: Context): TestMediaServer = server ?: synchronized(this) {
        // A fixed port keeps the saved test provider working after the app restarts; any free port if it is taken.
        server
            ?: (
                runCatching { TestMediaServer(context.applicationContext.assets, FIXED_PORT) }.getOrNull()
                    ?: TestMediaServer(context.applicationContext.assets)
                )
                .also { server = it }
    }

    /** Starts the test server with the app, so a saved test provider works right after a restart. */
    fun start(context: Context) {
        server(context)
    }

    /** Device tests set this to read TMDB from the in-process test server instead of TMDB (ADR-0038). */
    @Volatile
    var useTestTmdb: Boolean = false

    /** Where TMDB is read from: the test server's fake when [useTestTmdb], otherwise null (TMDB itself). */
    fun tmdbBase(context: Context): String? = if (useTestTmdb) server(context).url("/tmdb/3") else null

    /** Server address and the canary login of the in-process test Xtream panel. */
    fun testProvider(context: Context): Triple<String, String, String>? =
        Triple(server(context).url(""), TestPanel.USERNAME, TestPanel.PASSWORD)

    fun list(context: Context): List<DeveloperStream> {
        fun stream(id: String, label: String, path: String, protocol: StreamProtocol, mode: PlaybackMode) = DeveloperStream(id, label) {
            PlaybackRequest(ResolvedMediaSource(SensitiveUrl.of(server(context).url(path)), protocol, emptyMap(), emptyMap()), mode)
        }
        return listOf(
            stream("hls-live", "Live — HLS", "/hls/live.m3u8", StreamProtocol.HLS, PlaybackMode.LIVE),
            stream("ts-live", "Live — MPEG-TS", "/live.ts", StreamProtocol.PROGRESSIVE_TS, PlaybackMode.LIVE),
            stream("hls-vod", "Movie — HLS", "/hls/vod.m3u8", StreamProtocol.HLS, PlaybackMode.VOD),
            stream("mp4-vod", "Movie — MP4", "/vod.mp4", StreamProtocol.PROGRESSIVE_MP4, PlaybackMode.VOD),
            stream("not-found", "Error — 404", "/missing.m3u8", StreamProtocol.HLS, PlaybackMode.LIVE),
            stream("unsupported", "Error — not a video", "/html.ts", StreamProtocol.PROGRESSIVE_TS, PlaybackMode.VOD),
            stream("tracks", "Movie — audio & subtitles", "/multi-track.mp4", StreamProtocol.PROGRESSIVE_MP4, PlaybackMode.VOD),
        )
    }
}
