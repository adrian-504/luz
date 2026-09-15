package app.iptvplayer.platform.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
import app.iptvplayer.domain.model.StreamProtocol
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

/** What to play. The source lives only in memory for this session (docs/PLAYBACK.md §2). */
class PlaybackRequest(val source: ResolvedMediaSource, val mode: PlaybackMode, val startPosition: Duration? = null)

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

    private val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var surfaceView: SurfaceView? = null

    private val session = PlaybackSession(HandlerScheduler(handler), timeouts, SessionCallbacks())
    override val snapshot: StateFlow<PlaybackSnapshot> = session.snapshot

    private val mutableAspectRatio = MutableStateFlow<Float?>(null)
    override val videoAspectRatio: StateFlow<Float?> = mutableAspectRatio.asStateFlow()

    private val mutableDiagnostics = MutableStateFlow(PlaybackDiagnostics())
    override val diagnostics: StateFlow<PlaybackDiagnostics> = mutableDiagnostics.asStateFlow()

    private var prepareCalledAtMs = 0L
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
        mutableDiagnostics.value =
            PlaybackDiagnostics(streamType = protocol, connection = raw.substringBefore("://", "").lowercase().ifEmpty { null })
        session.prepare(request.mode)

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
        val item = MediaItem.Builder().setUri(raw).setMimeType(mimeType(protocol)).build()
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

    override fun duration(): Duration? {
        if (session.currentMode == PlaybackMode.LIVE || player.isCurrentMediaItemLive) return null
        return player.duration.takeIf { it != C.TIME_UNSET }?.milliseconds
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
            updateDiagnostics { current ->
                current.copy(timeToFirstFrameMs = current.timeToFirstFrameMs ?: (SystemClock.elapsedRealtime() - prepareCalledAtMs))
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
            updateDiagnostics { current ->
                current.copy(timeToFirstAudioMs = current.timeToFirstAudioMs ?: (SystemClock.elapsedRealtime() - prepareCalledAtMs))
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

        fun mimeType(protocol: StreamProtocol): String? = when (protocol) {
            StreamProtocol.HLS -> MimeTypes.APPLICATION_M3U8
            StreamProtocol.PROGRESSIVE_TS -> MimeTypes.VIDEO_MP2T
            StreamProtocol.PROGRESSIVE_MP4 -> MimeTypes.VIDEO_MP4
            StreamProtocol.MATROSKA -> MimeTypes.VIDEO_MATROSKA
            else -> null
        }
    }
}
