package app.iptvplayer.tv.developer

import android.content.Context
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.platform.playback.PlaybackRequest
import app.iptvplayer.protocols.media.ResolvedMediaSource
import app.iptvplayer.testing.TestMediaServer

/** Debug build: synthetic streams from an in-process [TestMediaServer] (tooling/fixtures/media). */
object DeveloperStreams {
    @Volatile
    private var server: TestMediaServer? = null

    private fun server(context: Context): TestMediaServer = server ?: synchronized(this) {
        server ?: TestMediaServer(context.applicationContext.assets).also { server = it }
    }

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
        )
    }
}
