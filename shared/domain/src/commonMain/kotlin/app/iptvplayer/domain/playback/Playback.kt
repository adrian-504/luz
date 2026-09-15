package app.iptvplayer.domain.playback

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public enum class PlaybackState { IDLE, PREPARING, PLAYING, PAUSED, BUFFERING, ERROR, ENDED }

public enum class PlaybackEvent {
    PREPARE,
    FIRST_FRAME,
    PAUSE,
    PLAY,
    BUFFER_START,
    BUFFER_END,
    ERROR,
    TIMEOUT_PREPARE,
    STALL_TIMEOUT,
    ENDED,
    RETRY,
    STOP,
    SEEK,
}

public enum class PlaybackMode { LIVE, VOD }

/**
 * The playback contract's transition function (docs/PLAYBACK.md §3, ADR-0019). Native controllers implement the
 * same table; both must pass the vectors in tooling/fixtures/playback/state-machine.json.
 */
public object PlaybackStateMachine {
    /** Returns the next state, or null when the event is ignored in [state]. */
    public fun transition(state: PlaybackState, event: PlaybackEvent, mode: PlaybackMode): PlaybackState? {
        if (event == PlaybackEvent.STOP) return PlaybackState.IDLE
        if (event == PlaybackEvent.PREPARE) return PlaybackState.PREPARING
        val endedTarget = if (mode == PlaybackMode.VOD) PlaybackState.ENDED else PlaybackState.ERROR
        return when (state) {
            PlaybackState.IDLE -> null
            PlaybackState.PREPARING -> when (event) {
                PlaybackEvent.FIRST_FRAME -> PlaybackState.PLAYING
                PlaybackEvent.ERROR, PlaybackEvent.TIMEOUT_PREPARE -> PlaybackState.ERROR
                PlaybackEvent.ENDED -> endedTarget
                else -> null
            }
            PlaybackState.PLAYING -> when (event) {
                PlaybackEvent.PAUSE -> PlaybackState.PAUSED
                PlaybackEvent.BUFFER_START -> PlaybackState.BUFFERING
                PlaybackEvent.ERROR -> PlaybackState.ERROR
                PlaybackEvent.ENDED -> endedTarget
                PlaybackEvent.SEEK -> PlaybackState.PLAYING
                else -> null
            }
            PlaybackState.PAUSED -> when (event) {
                PlaybackEvent.PLAY -> PlaybackState.PLAYING
                PlaybackEvent.ERROR -> PlaybackState.ERROR
                PlaybackEvent.SEEK -> PlaybackState.PAUSED
                else -> null
            }
            PlaybackState.BUFFERING -> when (event) {
                PlaybackEvent.PAUSE -> PlaybackState.PAUSED
                PlaybackEvent.BUFFER_END -> PlaybackState.PLAYING
                PlaybackEvent.ERROR, PlaybackEvent.STALL_TIMEOUT -> PlaybackState.ERROR
                PlaybackEvent.ENDED -> endedTarget
                PlaybackEvent.SEEK -> PlaybackState.BUFFERING
                else -> null
            }
            PlaybackState.ERROR -> if (event == PlaybackEvent.RETRY) PlaybackState.PREPARING else null
            PlaybackState.ENDED -> if (event == PlaybackEvent.SEEK && mode == PlaybackMode.VOD) PlaybackState.BUFFERING else null
        }
    }

    public fun next(state: PlaybackState, event: PlaybackEvent, mode: PlaybackMode): PlaybackState = transition(state, event, mode) ?: state
}

/** Stable playback error taxonomy shared by all native controllers (docs/PLAYBACK.md §5). */
public enum class PlaybackErrorCode(public val retryable: Boolean) {
    NET_OFFLINE(true),
    NET_DNS(true),
    NET_TLS(false),
    NET_TIMEOUT(true),
    HTTP_AUTH(false),
    HTTP_NOT_FOUND(false),
    HTTP_CONNECTION_LIMIT(false),
    HTTP_SERVER(true),
    SRC_UNSUPPORTED_PROTOCOL(false),
    SRC_UNSUPPORTED_CONTAINER(false),
    SRC_UNSUPPORTED_CODEC(false),
    SRC_MANIFEST_MALFORMED(false),
    SRC_BEHIND_LIVE_WINDOW(true),
    SRC_ENDED_UNEXPECTEDLY(true),
    DRM_FAILED(false),
    TIMEOUT_PREPARE(true),
    TIMEOUT_STALL(true),
    DECODER_FAILURE(true),
    UNKNOWN(false),
    ;

    public val messageKey: String get() = "playback.error." + name.lowercase()
}

/** Automatic recovery policy (docs/PLAYBACK.md §3 "Recovery policy"). Initial values, tuned in Phase 6. */
public object PlaybackRetryPolicy {
    private val LIVE_BACKOFF = listOf(1.seconds, 2.seconds, 4.seconds)
    private val VOD_BACKOFF = listOf(1.seconds, 2.seconds)
    private const val DECODER_RETRIES = 1

    /** Delay before retry number [attempt] (1-based), or null when no further automatic retry is allowed. */
    public fun delayBeforeRetry(code: PlaybackErrorCode, mode: PlaybackMode, attempt: Int): Duration? {
        require(attempt >= 1) { "attempt is 1-based" }
        if (!code.retryable) return null
        if (code == PlaybackErrorCode.SRC_BEHIND_LIVE_WINDOW && mode == PlaybackMode.LIVE) {
            return if (attempt <= LIVE_BACKOFF.size) Duration.ZERO else null
        }
        if (code == PlaybackErrorCode.DECODER_FAILURE) return if (attempt <= DECODER_RETRIES) Duration.ZERO else null
        val schedule = if (mode == PlaybackMode.LIVE) LIVE_BACKOFF else VOD_BACKOFF
        return schedule.getOrNull(attempt - 1)
    }

    /** Timers that move PREPARING/BUFFERING to ERROR (docs/PLAYBACK.md §3). */
    public fun prepareTimeout(mode: PlaybackMode): Duration = if (mode == PlaybackMode.LIVE) 15.seconds else 20.seconds

    public fun stallTimeout(mode: PlaybackMode): Duration = if (mode == PlaybackMode.LIVE) 12.seconds else 30.seconds
}
