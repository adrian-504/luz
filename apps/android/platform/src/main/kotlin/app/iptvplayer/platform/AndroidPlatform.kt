package app.iptvplayer.platform

import app.iptvplayer.domain.capability.PlatformCapabilities
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.model.DrmScheme
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.ports.Clock
import kotlin.time.Instant

/** What this Android build can play (Media3 modules in ADR-0024): HLS and progressive TS/MP4/MKV; no DASH, RTMP, RTSP, UDP. */
val AndroidPlatformCapabilities = PlatformCapabilities(
    protocols = mapOf(
        StreamProtocol.HLS to Support.SUPPORTED,
        StreamProtocol.PROGRESSIVE_TS to Support.SUPPORTED,
        StreamProtocol.PROGRESSIVE_MP4 to Support.SUPPORTED,
        StreamProtocol.MATROSKA to Support.SUPPORTED,
        StreamProtocol.DASH to Support.UNSUPPORTED,
        StreamProtocol.RTMP to Support.UNSUPPORTED,
        StreamProtocol.RTSP to Support.UNSUPPORTED,
        StreamProtocol.UDP to Support.UNSUPPORTED,
    ),
    drmSchemes = mapOf(
        DrmScheme.WIDEVINE to Support.UNKNOWN,
        DrmScheme.PLAYREADY to Support.UNKNOWN,
        DrmScheme.CLEARKEY to Support.UNKNOWN,
    ),
    multipleAudioTracks = Support.SUPPORTED,
    subtitles = Support.SUPPORTED,
)

object SystemClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())

    override fun monotonicNanos(): Long = System.nanoTime()
}
