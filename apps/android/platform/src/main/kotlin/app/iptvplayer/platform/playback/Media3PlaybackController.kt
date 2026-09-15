package app.iptvplayer.platform.playback

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import app.iptvplayer.domain.model.AudioTrack
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.SubtitleFormat
import app.iptvplayer.domain.model.SubtitleTrack
import app.iptvplayer.domain.model.TrackOrigin
import app.iptvplayer.domain.model.TrackSet
import app.iptvplayer.domain.playback.PlaybackErrorCode
import app.iptvplayer.domain.playback.PlaybackEvent
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.protocols.media.ResolvedMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What to play. The source lives only in memory for this session (docs/PLAYBACK.md §2). [title] and [subtitle] are shown by
 * the system's media controls (MediaSession); they must never contain URLs or credentials.
 */
class PlaybackRequest(
    val source: ResolvedMediaSource,
    val mode: PlaybackMode,
    val startPosition: Duration? = null,
    val title: String? = null,
    val subtitle: String? = null,
    /** `SystemClock.elapsedRealtime()` of the key press that asked for this stream, for channel-switch timing. */
    val intentAtMs: Long? = null,
    /** True when the stream was resolved ahead of the key press (PLAYBACK.md §4 tier T0). */
    val prepared: Boolean = false,
) {
    fun copy(
        title: String? = this.title,
        subtitle: String? = this.subtitle,
        intentAtMs: Long? = this.intentAtMs,
        prepared: Boolean = this.prepared,
    ) = PlaybackRequest(source, mode, startPosition, title, subtitle, intentAtMs, prepared)
}

/** One subtitle cue on screen: text cues carry [text], bitmap subtitles (for example DVB) carry [bitmap]. */
class SubtitleCue(val text: CharSequence?, val bitmap: Bitmap?)

/** Values for the diagnostics panel and local telemetry (docs/PLAYBACK.md §6). Never contains URLs or credentials. */
data class PlaybackDiagnostics(
    val streamType: StreamProtocol? = null,
    /** "https" or "http" (cleartext). */
    val connection: String? = null,
    val httpStatus: Int? = null,
    val videoCodec: String? = null,
    val resolution: String? = null,
    val videoBitrate: Int? = null,
    val audioCodec: String? = null,
    val bandwidthEstimate: Long? = null,
    val bufferedAheadMs: Long = 0,
    val droppedFrames: Int = 0,
    val rebufferCount: Int = 0,
    val retryCount: Int = 0,
    val timeToFirstFrameMs: Long? = null,
    val timeToFirstAudioMs: Long? = null,
    /** Key press to first frame for a channel switch (includes the zap settle delay and resolving the stream). */
    val switchTimeMs: Long? = null,
    /** Whether the switched-to stream had been prepared ahead (tier T0 hit); null when not a channel switch. */
    val preparedHit: Boolean? = null,
    val lastErrorCode: PlaybackErrorCode? = null,
    /** Media3's error code name for developers, for example `ERROR_CODE_IO_BAD_HTTP_STATUS`. */
    val lastEngineError: String? = null,
)

/** The native playback contract (docs/PLAYBACK.md §2). Call from the main thread. */
interface PlaybackController {
    val snapshot: StateFlow<PlaybackSnapshot>
    val diagnostics: StateFlow<PlaybackDiagnostics>

    fun prepare(request: PlaybackRequest)

    fun play()

    fun pause()

    fun seekTo(position: Duration)

    fun stop()

    fun release()

    fun currentPosition(): Duration?

    /** Null for live streams and while unknown. */
    fun duration(): Duration?

    /** Audio and subtitle tracks of the current media (track ids are valid only for the current media). */
    val tracks: StateFlow<TrackSet>

    /** Subtitle cues to draw now; empty when subtitles are off or between cues. */
    val subtitleCues: StateFlow<List<SubtitleCue>>

    /** Selects an audio track; its language becomes the preferred audio language for later channels in this player. */
    fun setAudioTrack(id: String)

    /** Selects a subtitle track, or turns subtitles off with null; the choice carries over to later channels in this player. */
    fun setSubtitleTrack(id: String?)

    /** Width / height of the current video (pixel aspect applied), or null before the first video format. */
    val videoAspectRatio: StateFlow<Float?>

    /** Renders video into [view]; pass null to detach. */
    fun attachSurfaceView(view: SurfaceView?)

    /** Updates time-varying diagnostics (buffer ahead); the diagnostics panel calls it about once a second. */
    fun refreshDiagnostics()
}

/**
 * Media3 ExoPlayer implementation of [PlaybackController] (ADR-0024). Engine callbacks are translated into shared state
 * machine events on [PlaybackSession]; timers and retries live there.
 */
@OptIn(UnstableApi::class)
class Media3PlaybackController(
    private val context: Context,
    timeouts: PlaybackTimeouts = PlaybackTimeouts(),
    private val networkAvailable: () -> Boolean = { true },
) : PlaybackController {
    private val handler = Handler(Looper.getMainLooper())
    private val loadErrorPolicy = PlaybackLoadErrorPolicy()

    internal val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var surfaceView: SurfaceView? = null

    private val session = PlaybackSession(HandlerScheduler(handler), timeouts, SessionCallbacks())
    override val snapshot: StateFlow<PlaybackSnapshot> = session.snapshot

    private val mutableAspectRatio = MutableStateFlow<Float?>(null)
    override val videoAspectRatio: StateFlow<Float?> = mutableAspectRatio.asStateFlow()

    private val mutableTracks = MutableStateFlow(TrackSet())
    override val tracks: StateFlow<TrackSet> = mutableTracks.asStateFlow()

    private val mutableCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    override val subtitleCues: StateFlow<List<SubtitleCue>> = mutableCues.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(PlaybackDiagnostics())
    override val diagnostics: StateFlow<PlaybackDiagnostics> = mutableDiagnostics.asStateFlow()

    private var prepareCalledAtMs = 0L
    private var intentAtMs: Long? = null
    private var lastPositionMs = 0L

    init {
        RedactingMedia3Logger.install()
        player.addListener(PlayerEvents())
        player.addAnalyticsListener(AnalyticsEvents())
    }

    override fun prepare(request: PlaybackRequest) {
        val protocol = request.source.protocol
        val raw = request.source.url.unsafeRawValue()
        prepareCalledAtMs = SystemClock.elapsedRealtime()
        lastPositionMs = request.startPosition?.inWholeMilliseconds ?: 0
        intentAtMs = request.intentAtMs
        mutableDiagnostics.value = PlaybackDiagnostics(
            streamType = protocol,
            connection = raw.substringBefore("://", "").lowercase().ifEmpty { null },
            preparedHit = request.prepared.takeIf { request.intentAtMs != null },
        )
        session.prepare(request.mode)
        mutableTracks.value = TrackSet()
        mutableCues.value = emptyList()

        if (protocol in UNSUPPORTED) {
            player.stop()
            fail(PlaybackErrorCode.SRC_UNSUPPORTED_PROTOCOL, engineError = null)
            return
        }
        val headers = request.source.headers
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers["User-Agent"] ?: DEFAULT_USER_AGENT)
            .setConnectTimeoutMs(NETWORK_TIMEOUT_MS)
            .setReadTimeoutMs(NETWORK_TIMEOUT_MS)
            // No automatic scheme changes on redirect; an https→http downgrade must never happen silently (SECURITY.md §5).
            .setAllowCrossProtocolRedirects(false)
            .setDefaultRequestProperties(
                headers.filterKeys {
                    it != "User-Agent"
                } + request.source.sensitiveHeaders.mapValues { it.value.unsafeValue() },
            )
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(context, http),
        ).setLoadErrorHandlingPolicy(loadErrorPolicy)
        val metadata = MediaMetadata.Builder().setTitle(request.title).setSubtitle(request.subtitle).setArtist(request.subtitle).build()
        val item = MediaItem.Builder().setUri(raw).setMimeType(mimeType(protocol)).setMediaMetadata(metadata).build()
        player.setMediaSource(mediaSourceFactory.createMediaSource(item), lastPositionMs)
        player.playWhenReady = true
        player.prepare()
    }

    override fun play() {
        if (!session.dispatch(PlaybackEvent.PLAY)) return
        player.play()
        if (player.playbackState == Player.STATE_BUFFERING) onBufferingStarted()
    }

    override fun pause() {
        if (session.dispatch(PlaybackEvent.PAUSE)) player.pause()
    }

    override fun seekTo(position: Duration) {
        if (session.currentMode == PlaybackMode.VOD && session.dispatch(PlaybackEvent.SEEK)) player.seekTo(position.inWholeMilliseconds)
    }

    override fun stop() {
        mutableTracks.value = TrackSet()
        mutableCues.value = emptyList()
        session.dispatch(PlaybackEvent.STOP)
        player.stop()
        player.clearMediaItems()
    }

    override fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
        player.release()
    }

    override fun currentPosition(): Duration? = if (session.currentState ==
        PlaybackState.IDLE
    ) {
        null
    } else {
        player.currentPosition.milliseconds
    }

    internal val isLive: Boolean get() = session.currentMode == PlaybackMode.LIVE

    override fun duration(): Duration? {
        if (session.currentMode == PlaybackMode.LIVE || player.isCurrentMediaItemLive) return null
        return player.duration.takeIf { it != C.TIME_UNSET }?.milliseconds
    }

    override fun setAudioTrack(id: String) {
        val (group, index) = trackById(id, C.TRACK_TYPE_AUDIO) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .setPreferredAudioLanguage(group.getTrackFormat(index).language)
            .build()
    }

    override fun setSubtitleTrack(id: String?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (id == null) {
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val (group, index) = trackById(id, C.TRACK_TYPE_TEXT) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .setPreferredTextLanguage(group.getTrackFormat(index).language)
        }
        player.trackSelectionParameters = builder.build()
    }

    /** Track ids are "group:track" indexes into the player's current tracks. */
    private fun trackById(id: String, type: Int): Pair<Tracks.Group, Int>? {
        val parts = id.split(':').mapNotNull { it.toIntOrNull() }
        if (parts.size != 2) return null
        val group = player.currentTracks.groups.getOrNull(parts[0])?.takeIf { it.type == type } ?: return null
        return (group to parts[1]).takeIf { parts[1] in 0 until group.length && group.isTrackSupported(parts[1]) }
    }

    private fun publishTracks(tracks: Tracks) {
        val audio = mutableListOf<AudioTrack>()
        val subtitles = mutableListOf<SubtitleTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val id = "$groupIndex:$index"
                val language = format.language?.takeUnless { it == C.LANGUAGE_UNDETERMINED }
                val isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += AudioTrack(
                        id = id,
                        language = language,
                        label = format.label,
                        codec = format.codecs ?: format.sampleMimeType,
                        channelCount = format.channelCount.takeIf { it != Format.NO_VALUE },
                        isDefault = isDefault,
                        isSelected = group.isTrackSelected(index),
                    )
                    C.TRACK_TYPE_TEXT -> subtitles += SubtitleTrack(
                        id = id,
                        language = language,
                        label = format.label,
                        // Media3 parses subtitles while extracting; the original format is then kept in `codecs`.
                        format = subtitleFormat(
                            if (format.sampleMimeType ==
                                MimeTypes.APPLICATION_MEDIA3_CUES
                            ) {
                                format.codecs
                            } else {
                                format.sampleMimeType
                            },
                        ),
                        isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        isDefault = isDefault,
                        isSelected = group.isTrackSelected(index),
                        origin = TrackOrigin.EMBEDDED,
                    )
                }
            }
        }
        mutableTracks.value = TrackSet(audio, subtitles)
    }

    override fun attachSurfaceView(view: SurfaceView?) {
        if (view === surfaceView) return
        surfaceView?.let { player.clearVideoSurfaceView(it) }
        surfaceView = view
        view?.let { player.setVideoSurfaceView(it) }
    }

    /** Renders video into a plain [Surface] (device tests use an off-screen texture). */
    fun attachSurface(surface: Surface?) {
        player.setVideoSurface(surface)
    }

    override fun refreshDiagnostics() {
        updateDiagnostics { it.copy(bufferedAheadMs = player.totalBufferedDuration) }
    }

    private fun fail(code: PlaybackErrorCode, engineError: String?, httpStatus: Int? = null) {
        updateDiagnostics { it.copy(lastErrorCode = code, lastEngineError = engineError, httpStatus = httpStatus ?: it.httpStatus) }
        session.dispatch(PlaybackEvent.ERROR, code)
    }

    private fun onBufferingStarted() {
        if (session.dispatch(PlaybackEvent.BUFFER_START)) updateDiagnostics { it.copy(rebufferCount = it.rebufferCount + 1) }
    }

    private inline fun updateDiagnostics(change: (PlaybackDiagnostics) -> PlaybackDiagnostics) {
        mutableDiagnostics.value = change(mutableDiagnostics.value)
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> onBufferingStarted()
                Player.STATE_READY -> {
                    session.dispatch(PlaybackEvent.BUFFER_END)
                    // Audio-only streams never render a video frame; READY while playing is their first frame.
                    val hasVideo = player.currentTracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
                    if (session.currentState == PlaybackState.PREPARING && !hasVideo && player.playWhenReady) firstFrame()
                }
                Player.STATE_ENDED -> session.dispatch(PlaybackEvent.ENDED)
                // IDLE follows stop() or an error, both already handled where they originate.
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                mutableAspectRatio.value = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            publishTracks(tracks)
        }

        override fun onCues(cueGroup: CueGroup) {
            mutableCues.value = cueGroup.cues.filter { it.text != null || it.bitmap != null }.map { SubtitleCue(it.text, it.bitmap) }
        }

        override fun onRenderedFirstFrame() {
            if (session.currentState == PlaybackState.PREPARING) firstFrame()
        }

        override fun onPlayerError(error: PlaybackException) {
            lastPositionMs = player.currentPosition
            val causes = generateSequence<Throwable>(error) { it.cause }.take(MAX_CAUSES).toList()
            val status = causes.firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode }
            val code = Media3ErrorMapper.map(error.errorCode, status, causes, networkAvailable())
            fail(code, error.errorCodeName, status)
        }

        private fun firstFrame() {
            // Diagnostics first, so observers of the PLAYING state already see the timing.
            val now = SystemClock.elapsedRealtime()
            updateDiagnostics { current ->
                current.copy(
                    timeToFirstFrameMs = current.timeToFirstFrameMs ?: (now - prepareCalledAtMs),
                    switchTimeMs = current.switchTimeMs ?: intentAtMs?.let { now - it },
                )
            }
            session.dispatch(PlaybackEvent.FIRST_FRAME)
        }
    }

    private inner class AnalyticsEvents : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            updateDiagnostics {
                it.copy(
                    videoCodec = format.codecs ?: format.sampleMimeType,
                    resolution = if (format.width > 0 && format.height > 0) "${format.width}x${format.height}" else null,
                    videoBitrate = format.bitrate.takeIf { bitrate -> bitrate != Format.NO_VALUE },
                )
            }
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            updateDiagnostics { it.copy(audioCodec = format.codecs ?: format.sampleMimeType) }
        }

        override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
            // Reported once the audio position has advanced, a little after sound began; playoutStartSystemTimeMs is the
            // wall-clock start of playout, converted here to the elapsed-realtime base of prepareCalledAtMs.
            val startedAgoMs = (System.currentTimeMillis() - playoutStartSystemTimeMs).coerceAtLeast(0)
            val startedAtMs = maxOf(prepareCalledAtMs, SystemClock.elapsedRealtime() - startedAgoMs)
            updateDiagnostics { current ->
                current.copy(
                    timeToFirstAudioMs = current.timeToFirstAudioMs ?: (startedAtMs - prepareCalledAtMs),
                )
            }
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            updateDiagnostics { it.copy(droppedFrames = it.droppedFrames + droppedFrames) }
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            updateDiagnostics { it.copy(bandwidthEstimate = bitrateEstimate) }
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val status = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return
            updateDiagnostics { it.copy(httpStatus = status) }
        }
    }

    private inner class SessionCallbacks : PlaybackSession.Callbacks {
        override fun onTimedOut(code: PlaybackErrorCode) {
            lastPositionMs = player.currentPosition
            updateDiagnostics { it.copy(lastErrorCode = code, lastEngineError = null) }
            player.stop()
        }

        override fun onRetry(code: PlaybackErrorCode, attempt: Int) {
            updateDiagnostics { it.copy(retryCount = it.retryCount + 1) }
            if (session.currentMode == PlaybackMode.LIVE) player.seekToDefaultPosition() else player.seekTo(lastPositionMs)
            player.playWhenReady = true
            player.prepare()
        }
    }

    private class HandlerScheduler(private val handler: Handler) : PlaybackScheduler {
        override fun schedule(delay: Duration, action: () -> Unit): Cancellable {
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delay.inWholeMilliseconds)
            return Cancellable { handler.removeCallbacks(runnable) }
        }
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "IPTVPlayer/0.1 (Android)"
        const val NETWORK_TIMEOUT_MS = 8_000
        const val MAX_CAUSES = 8
        val UNSUPPORTED = setOf(StreamProtocol.DASH, StreamProtocol.RTMP, StreamProtocol.RTSP, StreamProtocol.UDP)

        fun subtitleFormat(mimeType: String?): SubtitleFormat = when (mimeType) {
            MimeTypes.TEXT_VTT -> SubtitleFormat.WEBVTT
            MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> SubtitleFormat.CEA608
            MimeTypes.APPLICATION_CEA708 -> SubtitleFormat.CEA708
            MimeTypes.APPLICATION_TTML -> SubtitleFormat.TTML
            MimeTypes.APPLICATION_DVBSUBS -> SubtitleFormat.DVB_BITMAP
            MimeTypes.APPLICATION_PGS -> SubtitleFormat.PGS
            MimeTypes.APPLICATION_SUBRIP -> SubtitleFormat.SRT
            else -> SubtitleFormat.OTHER
        }

        fun mimeType(protocol: StreamProtocol): String? = when (protocol) {
            StreamProtocol.HLS -> MimeTypes.APPLICATION_M3U8
            StreamProtocol.PROGRESSIVE_TS -> MimeTypes.VIDEO_MP2T
            StreamProtocol.PROGRESSIVE_MP4 -> MimeTypes.VIDEO_MP4
            StreamProtocol.MATROSKA -> MimeTypes.VIDEO_MATROSKA
            else -> null
        }
    }
}
