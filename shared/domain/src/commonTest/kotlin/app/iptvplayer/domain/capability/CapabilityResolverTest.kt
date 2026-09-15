package app.iptvplayer.domain.capability

import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentSelection
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.DrmDescriptor
import app.iptvplayer.domain.model.DrmScheme
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.XtreamStreamKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilityResolverTest {
    private val provider = Capabilities(
        liveTv = Support.SUPPORTED, epg = Support.SUPPORTED, movies = Support.SUPPORTED, series = Support.UNKNOWN,
        catchUp = Support.SUPPORTED, recording = Support.SUPPORTED, multipleAudioTracks = Support.SUPPORTED,
        subtitles = Support.UNKNOWN, drm = Support.SUPPORTED, maxConnections = 1,
        streamFormats = setOf(StreamFormat.HLS, StreamFormat.MPEG_TS), catchUpDays = 3,
    )

    // Resembles an AVPlayer-based platform: HLS yes, raw MPEG-TS and Matroska no, FairPlay only.
    private val applePlatform = PlatformCapabilities(
        protocols = mapOf(
            StreamProtocol.HLS to Support.SUPPORTED,
            StreamProtocol.PROGRESSIVE_MP4 to Support.SUPPORTED,
            StreamProtocol.PROGRESSIVE_TS to Support.UNSUPPORTED,
            StreamProtocol.MATROSKA to Support.UNSUPPORTED,
        ),
        drmSchemes = mapOf(DrmScheme.FAIRPLAY to Support.SUPPORTED, DrmScheme.WIDEVINE to Support.UNSUPPORTED),
        multipleAudioTracks = Support.SUPPORTED,
        subtitles = Support.SUPPORTED,
    )

    @Test
    fun supportConjunction() {
        assertEquals(Support.UNSUPPORTED, Support.UNKNOWN and Support.UNSUPPORTED)
        assertEquals(Support.UNKNOWN, Support.SUPPORTED and Support.UNKNOWN)
        assertEquals(Support.SUPPORTED, Support.SUPPORTED and Support.SUPPORTED)
    }

    @Test
    fun effectiveCombinesProviderPlatformAndUserLayers() {
        val effective = CapabilityResolver.effective(provider, applePlatform, ContentSelection(movies = false))
        assertEquals(Support.SUPPORTED, effective.liveTv)
        assertEquals(Support.UNSUPPORTED, effective.movies, "user disabled movies")
        assertEquals(Support.UNKNOWN, effective.series, "not yet discovered stays unknown")
        assertEquals(Support.UNKNOWN, effective.subtitles)
        assertEquals(Support.UNSUPPORTED, effective.recording, "recording deferred in V1 regardless of provider")
        assertEquals(setOf(StreamFormat.HLS), effective.streamFormats, "platform cannot play MPEG-TS")
        assertEquals(1, effective.maxConnections)
        assertEquals(3, effective.catchUpDays)
    }

    @Test
    fun undiscoveredDefaultsNeverClaimRecording() {
        assertEquals(Support.UNSUPPORTED, Capabilities.UNDISCOVERED.recording)
        assertEquals(Support.UNKNOWN, Capabilities.UNDISCOVERED.liveTv)
    }

    private fun source(protocol: StreamProtocol, drm: DrmScheme? = null) = MediaSource(
        id = MediaSourceId("ms_test"),
        owner = ContentRef(ContentType.MOVIE, "mov_test"),
        locator = MediaLocator.XtreamStream(XtreamStreamKind.MOVIE, "2001", "mkv"),
        protocolHint = protocol,
        headers = MediaHeaders(),
        drm = drm?.let { DrmDescriptor(it, null, MediaHeaders()) },
        codecHints = null,
        priority = 0,
    )

    @Test
    fun playabilityExplainsUnsupportedItems() {
        assertEquals(Playability.Supported, CapabilityResolver.playability(source(StreamProtocol.HLS), applePlatform))
        assertEquals(
            Playability.Unsupported(UnplayableReason.PROTOCOL_NOT_SUPPORTED),
            CapabilityResolver.playability(source(StreamProtocol.MATROSKA), applePlatform),
        )
        assertEquals(
            Playability.Unsupported(UnplayableReason.DRM_NOT_SUPPORTED),
            CapabilityResolver.playability(source(StreamProtocol.HLS, DrmScheme.WIDEVINE), applePlatform),
        )
        assertEquals(Playability.Unknown, CapabilityResolver.playability(source(StreamProtocol.DASH), applePlatform))
        assertEquals(Playability.Unknown, CapabilityResolver.playability(source(StreamProtocol.UNKNOWN), applePlatform))
        assertEquals(Playability.Unknown, CapabilityResolver.playability(source(StreamProtocol.HLS, DrmScheme.PLAYREADY), applePlatform))
    }
}
