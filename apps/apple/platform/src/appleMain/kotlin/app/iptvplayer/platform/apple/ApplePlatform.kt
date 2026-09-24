package app.iptvplayer.platform.apple

import app.iptvplayer.domain.capability.PlatformCapabilities
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.model.DrmScheme
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.ports.Clock
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * What an Apple device can play with AVPlayer and MobileVLCKit together (ADR-0041): HLS and MP4 through AVPlayer; plain
 * MPEG-TS and Matroska through VLCKit. Live channels still ask for HLS first (the app resolves them with
 * `PreferredStreamFormat.HLS`), so AVPlayer plays them wherever the account allows it.
 */
public val ApplePlatformCapabilities: PlatformCapabilities = PlatformCapabilities(
    protocols = mapOf(
        StreamProtocol.HLS to Support.SUPPORTED,
        StreamProtocol.PROGRESSIVE_MP4 to Support.SUPPORTED,
        StreamProtocol.PROGRESSIVE_TS to Support.SUPPORTED,
        StreamProtocol.MATROSKA to Support.SUPPORTED,
        StreamProtocol.DASH to Support.UNSUPPORTED,
        StreamProtocol.RTMP to Support.UNSUPPORTED,
        StreamProtocol.RTSP to Support.UNSUPPORTED,
        StreamProtocol.UDP to Support.UNSUPPORTED,
    ),
    drmSchemes = mapOf(
        DrmScheme.WIDEVINE to Support.UNSUPPORTED,
        DrmScheme.PLAYREADY to Support.UNSUPPORTED,
        DrmScheme.CLEARKEY to Support.UNKNOWN,
    ),
    multipleAudioTracks = Support.SUPPORTED,
    subtitles = Support.SUPPORTED,
)

public object AppleClock : Clock {
    private val start = TimeSource.Monotonic.markNow()

    override fun now(): Instant = kotlin.time.Clock.System.now()

    override fun monotonicNanos(): Long = start.elapsedNow().inWholeNanoseconds
}
