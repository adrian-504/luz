package app.iptvplayer.platform.playback

import app.iptvplayer.domain.playback.PlaybackErrorCode
import app.iptvplayer.domain.playback.PlaybackEvent
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackRetryPolicy
import app.iptvplayer.domain.playback.PlaybackState
import app.iptvplayer.domain.playback.PlaybackStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A pending timer that can be cancelled. */
fun interface Cancellable {
    fun cancel()
}

/** Runs actions after a delay on the playback thread. The Android implementation posts to the main looper. */
fun interface PlaybackScheduler {
    fun schedule(delay: Duration, action: () -> Unit): Cancellable
}

/** Timer values (docs/PLAYBACK.md §3); defaults come from the shared policy, tests may shorten them. */
data class PlaybackTimeouts(
    val prepareLive: Duration = PlaybackRetryPolicy.prepareTimeout(PlaybackMode.LIVE),
    val prepareVod: Duration = PlaybackRetryPolicy.prepareTimeout(PlaybackMode.VOD),
    val stallLive: Duration = PlaybackRetryPolicy.stallTimeout(PlaybackMode.LIVE),
    val stallVod: Duration = PlaybackRetryPolicy.stallTimeout(PlaybackMode.VOD),
    /** Playback must run this long before the retry budget is restored, so a stream that keeps failing stops retrying. */
    val stablePlayback: Duration = 10.seconds,
) {
    fun prepare(mode: PlaybackMode): Duration = if (mode == PlaybackMode.LIVE) prepareLive else prepareVod

    fun stall(mode: PlaybackMode): Duration = if (mode == PlaybackMode.LIVE) stallLive else stallVod
}

data class PlaybackSnapshot(
    val state: PlaybackState,
    val mode: PlaybackMode,
    /** The error shown to the user; set only in [PlaybackState.ERROR]. */
    val error: PlaybackErrorCode?,
    /** Automatic retries used since playback last ran stably. */
    val retryAttempt: Int,
    /** True while an automatic retry is scheduled. */
    val retryPending: Boolean,
)

/**
 * The engine-independent core of a native playback controller: applies the shared transition table
 * ([PlaybackStateMachine], tooling/fixtures/playback/state-machine.json), runs the prepare and stall timers and schedules
 * automatic retries per [PlaybackRetryPolicy]. Not thread-safe: call it from the playback thread only.
 */
class PlaybackSession(
    private val scheduler: PlaybackScheduler,
    private val timeouts: PlaybackTimeouts = PlaybackTimeouts(),
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** A timer moved the session to ERROR; the engine must stop loading. */
        fun onTimedOut(code: PlaybackErrorCode)

        /** An automatic retry moved the session back to PREPARING; the engine must prepare again. */
        fun onRetry(code: PlaybackErrorCode, attempt: Int)
    }

    private var state = PlaybackState.IDLE
    private var mode = PlaybackMode.VOD
    private var error: PlaybackErrorCode? = null
    private var attempts = 0
    private var prepareTimer: Cancellable? = null
    private var stallTimer: Cancellable? = null
    private var stableTimer: Cancellable? = null
    private var retryTimer: Cancellable? = null

    private val mutableSnapshot = MutableStateFlow(snapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()

    val currentState: PlaybackState get() = state
    val currentMode: PlaybackMode get() = mode

    /** Starts preparing a new source in [newMode] (also a channel change); resets the retry budget. */
    fun prepare(newMode: PlaybackMode) {
        mode = newMode
        dispatch(PlaybackEvent.PREPARE)
    }

    /**
     * Applies [event]; [errorCode] classifies [PlaybackEvent.ERROR]. Returns false when the event is ignored in the current
     * state (docs/PLAYBACK.md §3).
     */
    fun dispatch(event: PlaybackEvent, errorCode: PlaybackErrorCode? = null): Boolean {
        val from = state
        val to = PlaybackStateMachine.transition(from, event, mode) ?: return false

        if (event == PlaybackEvent.PREPARE || event == PlaybackEvent.STOP) {
            attempts = 0
            retryTimer = retryTimer.cancelled()
        }
        if (from == PlaybackState.PREPARING) prepareTimer = prepareTimer.cancelled()
        if (from == PlaybackState.BUFFERING) stallTimer = stallTimer.cancelled()
        if (from == PlaybackState.PLAYING) stableTimer = stableTimer.cancelled()

        state = to
        error = null
        when (to) {
            PlaybackState.PREPARING -> prepareTimer = scheduler.schedule(timeouts.prepare(mode)) { onTimer(PlaybackEvent.TIMEOUT_PREPARE) }
            PlaybackState.BUFFERING -> stallTimer = scheduler.schedule(timeouts.stall(mode)) { onTimer(PlaybackEvent.STALL_TIMEOUT) }
            PlaybackState.PLAYING -> stableTimer = scheduler.schedule(timeouts.stablePlayback) {
                attempts = 0
                publish()
            }
            PlaybackState.ERROR -> {
                val code = when (event) {
                    PlaybackEvent.TIMEOUT_PREPARE -> PlaybackErrorCode.TIMEOUT_PREPARE
                    PlaybackEvent.STALL_TIMEOUT -> PlaybackErrorCode.TIMEOUT_STALL
                    PlaybackEvent.ENDED -> PlaybackErrorCode.SRC_ENDED_UNEXPECTEDLY
                    else -> errorCode ?: PlaybackErrorCode.UNKNOWN
                }
                error = code
                if (event == PlaybackEvent.TIMEOUT_PREPARE || event == PlaybackEvent.STALL_TIMEOUT) callbacks.onTimedOut(code)
                scheduleRetry(code)
            }
            else -> Unit
        }
        publish()
        return true
    }

    private fun onTimer(event: PlaybackEvent) {
        dispatch(event)
    }

    private fun scheduleRetry(code: PlaybackErrorCode) {
        val attempt = attempts + 1
        val delay = PlaybackRetryPolicy.delayBeforeRetry(code, mode, attempt) ?: return
        attempts = attempt
        retryTimer = scheduler.schedule(delay) {
            retryTimer = null
            if (dispatch(PlaybackEvent.RETRY)) callbacks.onRetry(code, attempt)
        }
    }

    private fun snapshot() = PlaybackSnapshot(state, mode, error, attempts, retryTimer != null)

    private fun publish() {
        mutableSnapshot.value = snapshot()
    }

    private fun Cancellable?.cancelled(): Cancellable? {
        this?.cancel()
        return null
    }
}
