package app.iptvplayer.domain.capability

import app.iptvplayer.domain.model.ContentSelection
import app.iptvplayer.domain.model.DrmScheme
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.StreamProtocol

/** Tri-state support: UNKNOWN means "not yet discovered", which the UI treats differently from UNSUPPORTED. */
public enum class Support {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
    ;

    /** Conjunction: any UNSUPPORTED wins, then any UNKNOWN, else SUPPORTED. */
    public infix fun and(other: Support): Support = when {
        this == UNSUPPORTED || other == UNSUPPORTED -> UNSUPPORTED
        this == UNKNOWN || other == UNKNOWN -> UNKNOWN
        else -> SUPPORTED
    }
}

/** Stream output formats a provider offers (Xtream `allowed_output_formats`). */
public enum class StreamFormat { HLS, MPEG_TS, RTMP }

public data class Capabilities(
    public val liveTv: Support,
    public val epg: Support,
    public val movies: Support,
    public val series: Support,
    public val catchUp: Support,
    public val recording: Support,
    public val multipleAudioTracks: Support,
    public val subtitles: Support,
    public val drm: Support,
    public val maxConnections: Int?,
    public val streamFormats: Set<StreamFormat>,
    public val catchUpDays: Int?,
) {
    public companion object {
        public val UNDISCOVERED: Capabilities = Capabilities(
            liveTv = Support.UNKNOWN,
            epg = Support.UNKNOWN,
            movies = Support.UNKNOWN,
            series = Support.UNKNOWN,
            catchUp = Support.UNKNOWN,
            recording = Support.UNSUPPORTED,
            multipleAudioTracks = Support.UNKNOWN,
            subtitles = Support.UNKNOWN,
            drm = Support.UNKNOWN,
            maxConnections = null,
            streamFormats = emptySet(),
            catchUpDays = null,
        )
    }
}

/** What this device and its native player can do; reported by the platform layer. */
public data class PlatformCapabilities(
    public val protocols: Map<StreamProtocol, Support>,
    public val drmSchemes: Map<DrmScheme, Support>,
    public val multipleAudioTracks: Support,
    public val subtitles: Support,
)

public sealed interface Playability {
    public data object Supported : Playability

    public data object Unknown : Playability

    public data class Unsupported(public val reason: UnplayableReason) : Playability
}

public enum class UnplayableReason { PROTOCOL_NOT_SUPPORTED, DRM_NOT_SUPPORTED }

/** The only place that combines provider, platform and user capability layers (docs/DOMAIN_MODEL.md §5). */
public object CapabilityResolver {
    public fun effective(provider: Capabilities, platform: PlatformCapabilities, selection: ContentSelection): Capabilities {
        val platformDrm = when {
            platform.drmSchemes.values.any { it == Support.SUPPORTED } -> Support.SUPPORTED
            platform.drmSchemes.values.any { it == Support.UNKNOWN } -> Support.UNKNOWN
            else -> Support.UNSUPPORTED
        }
        return Capabilities(
            liveTv = selected(selection.live, provider.liveTv),
            epg = selected(selection.epg, provider.epg),
            movies = selected(selection.movies, provider.movies),
            series = selected(selection.series, provider.series),
            catchUp = provider.catchUp,
            // Recording is deferred for V1 regardless of provider claims (ADR-0008).
            recording = Support.UNSUPPORTED,
            multipleAudioTracks = provider.multipleAudioTracks and platform.multipleAudioTracks,
            subtitles = provider.subtitles and platform.subtitles,
            drm = provider.drm and platformDrm,
            maxConnections = provider.maxConnections,
            streamFormats = provider.streamFormats.filterTo(LinkedHashSet()) {
                platform.protocols[it.protocol()] != Support.UNSUPPORTED
            },
            catchUpDays = provider.catchUpDays,
        )
    }

    public fun playability(source: MediaSource, platform: PlatformCapabilities): Playability {
        val protocol = if (source.protocolHint == StreamProtocol.UNKNOWN) {
            Support.UNKNOWN
        } else {
            platform.protocols[source.protocolHint] ?: Support.UNKNOWN
        }
        if (protocol == Support.UNSUPPORTED) return Playability.Unsupported(UnplayableReason.PROTOCOL_NOT_SUPPORTED)
        val drm = source.drm?.let { platform.drmSchemes[it.scheme] ?: Support.UNKNOWN } ?: Support.SUPPORTED
        if (drm == Support.UNSUPPORTED) return Playability.Unsupported(UnplayableReason.DRM_NOT_SUPPORTED)
        return if (protocol == Support.SUPPORTED && drm == Support.SUPPORTED) Playability.Supported else Playability.Unknown
    }

    private fun selected(enabledByUser: Boolean, provider: Support): Support = if (enabledByUser) provider else Support.UNSUPPORTED

    private fun StreamFormat.protocol(): StreamProtocol = when (this) {
        StreamFormat.HLS -> StreamProtocol.HLS
        StreamFormat.MPEG_TS -> StreamProtocol.PROGRESSIVE_TS
        StreamFormat.RTMP -> StreamProtocol.RTMP
    }
}
