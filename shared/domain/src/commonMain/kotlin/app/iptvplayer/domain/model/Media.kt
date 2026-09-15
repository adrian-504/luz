package app.iptvplayer.domain.model

import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.security.UrlTemplate

public enum class ContentType { CHANNEL, MOVIE, SERIES, EPISODE, PROGRAM }

/** Type-tagged reference to any content item; used by favorites and watch state. */
public data class ContentRef(public val type: ContentType, public val id: String)

public enum class StreamProtocol { HLS, DASH, PROGRESSIVE_TS, PROGRESSIVE_MP4, MATROSKA, RTMP, RTSP, UDP, UNKNOWN }

public enum class XtreamStreamKind { LIVE, MOVIE, SERIES }

/** Where a stream is; resolved to a concrete [app.iptvplayer.domain.security.SensitiveUrl] only at playback time. */
public sealed interface MediaLocator {
    public data class DirectUrl(public val template: UrlTemplate) : MediaLocator

    public data class XtreamStream(public val kind: XtreamStreamKind, public val streamId: String, public val extension: String?) :
        MediaLocator

    public data class XtreamTimeshift(public val streamId: String) : MediaLocator
}

public data class MediaHeaders(
    public val userAgent: String? = null,
    public val referrer: String? = null,
    public val custom: Map<String, String> = emptyMap(),
    /** Header values that are secrets (Cookie, Authorization) live in the secret store. */
    public val sensitive: Map<String, CredentialRef> = emptyMap(),
)

public enum class DrmScheme { WIDEVINE, PLAYREADY, CLEARKEY, FAIRPLAY }

public data class DrmDescriptor(public val scheme: DrmScheme, public val licenseUrl: UrlTemplate?, public val headers: MediaHeaders)

/** Declared by the source; never trusted over player-reported values. */
public data class CodecHints(
    public val video: String? = null,
    public val audio: String? = null,
    public val width: Int? = null,
    public val height: Int? = null,
    public val bitrate: Long? = null,
    public val frameRate: Double? = null,
)

public data class MediaSource(
    public val id: MediaSourceId,
    public val owner: ContentRef,
    public val locator: MediaLocator,
    public val protocolHint: StreamProtocol,
    public val headers: MediaHeaders,
    public val drm: DrmDescriptor?,
    public val codecHints: CodecHints?,
    public val priority: Int,
)

public enum class SubtitleFormat { WEBVTT, CEA608, CEA708, TTML, DVB_BITMAP, PGS, SRT, OTHER }

public enum class TrackOrigin { EMBEDDED, SIDECAR_DECLARED, EXTERNAL }

/** Runtime track reported by the native player; not persisted (preferences are). */
public data class AudioTrack(
    public val id: String,
    public val language: String?,
    public val label: String?,
    public val codec: String?,
    public val channelCount: Int?,
    public val isDefault: Boolean,
    public val isSelected: Boolean,
)

public data class SubtitleTrack(
    public val id: String,
    public val language: String?,
    public val label: String?,
    public val format: SubtitleFormat,
    public val isForced: Boolean,
    public val isDefault: Boolean,
    public val isSelected: Boolean,
    public val origin: TrackOrigin,
)
