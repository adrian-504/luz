package app.iptvplayer.platform.playback

import app.iptvplayer.domain.playback.PlaybackErrorCode
import app.iptvplayer.domain.playback.PlaybackEvent
import app.iptvplayer.domain.playback.PlaybackMode
import app.iptvplayer.domain.playback.PlaybackState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Virtual-time scheduler: timers fire only when the test advances time. */
private class FakeScheduler : PlaybackScheduler {
    private class Task(val at: Duration, val action: () -> Unit, var cancelled: Boolean = false)

    private val tasks = ArrayList<Task>()
    var now: Duration = Duration.ZERO
        private set

    override fun schedule(delay: Duration, action: () -> Unit): Cancellable {
        val task = Task(now + delay, action)
        tasks += task
        return Cancellable { task.cancelled = true }
    }

    fun advance(by: Duration) {
        val target = now + by
        while (true) {
            val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
            tasks.remove(next)
            now = next.at
            next.action()
        }
        now = target
    }
}

private class RecordingCallbacks : PlaybackSession.Callbacks {
    val timedOut = ArrayList<PlaybackErrorCode>()
    val retries = ArrayList<Pair<PlaybackErrorCode, Int>>()

    override fun onTimedOut(code: PlaybackErrorCode) {
        timedOut += code
    }

    override fun onRetry(code: PlaybackErrorCode, attempt: Int) {
        retries += code to attempt
    }
}

class PlaybackSessionTest {
    private val scheduler = FakeScheduler()
    private val callbacks = RecordingCallbacks()
    private val session = PlaybackSession(scheduler, PlaybackTimeouts(), callbacks)

    private fun state() = session.snapshot.value.state

    @Test
    fun passesEveryConformanceVector() {
        val fixture = File(System.getProperty("fixtures.dir"), "playback/state-machine.json")
        val vectors = Json.parseToJsonElement(fixture.readText()).jsonObject.getValue("vectors").jsonArray
        assertTrue(vectors.size >= 10)
        for (vector in vectors) {
            val name = vector.jsonObject.getValue("name").jsonPrimitive.content
            val mode = PlaybackMode.valueOf(vector.jsonObject.getValue("mode").jsonPrimitive.content.uppercase())
            val session = PlaybackSession(FakeScheduler(), PlaybackTimeouts(), RecordingCallbacks())
            for ((index, step) in vector.jsonObject.getValue("steps").jsonArray.withIndex()) {
                val event = PlaybackEvent.valueOf(step.jsonArray[0].jsonPrimitive.content.uppercase())
                val expected = PlaybackState.valueOf(step.jsonArray[1].jsonPrimitive.content)
                if (event == PlaybackEvent.PREPARE) session.prepare(mode) else session.dispatch(event, PlaybackErrorCode.NET_TIMEOUT)
                assertEquals(expected, session.snapshot.value.state, "vector $name step $index ($event)")
            }
        }
    }

    @Test
    fun prepareTimeoutStopsTheEngineAndRetriesWithBackoff() {
        session.prepare(PlaybackMode.LIVE)
        scheduler.advance(14.seconds)
        assertEquals(PlaybackState.PREPARING, state())
        scheduler.advance(1.seconds)
        assertEquals(PlaybackState.ERROR, state())
        assertEquals(PlaybackErrorCode.TIMEOUT_PREPARE, session.snapshot.value.error)
        assertEquals(listOf(PlaybackErrorCode.TIMEOUT_PREPARE), callbacks.timedOut)
        assertTrue(session.snapshot.value.retryPending)

        scheduler.advance(999.milliseconds)
        assertTrue(callbacks.retries.isEmpty())
        scheduler.advance(1.milliseconds)
        assertEquals(listOf(PlaybackErrorCode.TIMEOUT_PREPARE to 1), callbacks.retries)
        assertEquals(PlaybackState.PREPARING, state())
        assertNull(session.snapshot.value.error)
    }

    @Test
    fun liveRetriesThreeTimesThenGivesUp() {
        session.prepare(PlaybackMode.LIVE)
        val delays = listOf(1.seconds, 2.seconds, 4.seconds)
        for ((index, delay) in delays.withIndex()) {
            session.dispatch(PlaybackEvent.ERROR, PlaybackErrorCode.HTTP_SERVER)
            scheduler.advance(delay - 1.milliseconds)
            assertEquals(PlaybackState.ERROR, state(), "retry ${index + 1} waits its backoff")
            scheduler.advance(1.milliseconds)
            assertEquals(PlaybackState.PREPARING, state())
        }
        session.dispatch(PlaybackEvent.ERROR, PlaybackErrorCode.HTTP_SERVER)
        assertFalse(session.snapshot.value.retryPending)
        scheduler.advance(60.seconds)
        assertEquals(PlaybackState.ERROR, state())
        assertEquals(listOf(1, 2, 3), callbacks.retries.map { it.second })
    }

    @Test
    fun nonRetryableErrorsNeverRetry() {
        for (code in listOf(
            PlaybackErrorCode.HTTP_AUTH,
            PlaybackErrorCode.HTTP_NOT_FOUND,
            PlaybackErrorCode.SRC_UNSUPPORTED_CODEC,
            PlaybackErrorCode.UNKNOWN,
        )) {
            session.prepare(PlaybackMode.VOD)
            session.dispatch(PlaybackEvent.ERROR, code)
            assertFalse(session.snapshot.value.retryPending, "$code")
            assertEquals(code, session.snapshot.value.error)
        }
        scheduler.advance(60.seconds)
        assertTrue(callbacks.retries.isEmpty())
    }

    @Test
    fun stablePlaybackRestoresTheRetryBudgetButShortPlaybackDoesNot() {
        session.prepare(PlaybackMode.VOD)
        session.dispatch(PlaybackEvent.ERROR, PlaybackErrorCode.NET_TIMEOUT)
        scheduler.advance(1.seconds)
        session.dispatch(PlaybackEvent.FIRST_FRAME)
        scheduler.advance(5.seconds)
        assertEquals(1, session.snapshot.value.retryAttempt, "5 s of playback is not stable")
        session.dispatch(PlaybackEvent.ERROR, PlaybackErrorCode.NET_TIMEOUT)
        scheduler.advance(2.seconds)
        session.dispatch(PlaybackEvent.FIRST_FRAME)
        session.dispatch(PlaybackEvent.ERROR, PlaybackErrorCode.NET_TIMEOUT)
        assertFalse(session.snapshot.value.retryPending, "VOD budget of two retries is used up")

        session.prepare(PlaybackMode.VOD)
        session.dispatch(PlaybackEvent.FIRST_FRAME)
        scheduler.advance(10.seconds)
        assertEquals(0, session.snapshot.value.retryAttempt)
    }

    @Test
    fun stallTimeoutAndUnexpectedLiveEnd() {
        session.prepare(PlaybackMode.LIVE)
        session.dispatch(PlaybackEvent.FIRST_FRAME)
        session.dispatch(PlaybackEvent.BUFFER_START)
        scheduler.advance(11.seconds)
        session.dispatch(PlaybackEvent.BUFFER_END)
        session.dispatch(PlaybackEvent.BUFFER_START)
        scheduler.advance(11.seconds)
        assertEquals(PlaybackState.BUFFERING, state(), "the stall timer restarts with each buffering period")
        scheduler.advance(1.seconds)
        assertEquals(PlaybackErrorCode.TIMEOUT_STALL, session.snapshot.value.error)

        session.prepare(PlaybackMode.LIVE)
        session.dispatch(PlaybackEvent.FIRST_FRAME)
        session.dispatch(PlaybackEvent.ENDED)
        assertEquals(PlaybackErrorCode.SRC_ENDED_UNEXPECTEDLY, session.snapshot.value.error)
        assertTrue(session.snapshot.value.retryPending)
    }

    @Test
    fun stopAndChannelChangeCancelPendingWork() {
        session.prepare(PlaybackMode.LIVE)
        session.dispatch(PlaybackEvent.ERROR, PlaybackErrorCode.NET_DNS)
        session.dispatch(PlaybackEvent.STOP)
        assertFalse(session.snapshot.value.retryPending, "stop cancels the scheduled retry")
        scheduler.advance(60.seconds)
        assertEquals(PlaybackState.IDLE, state())
        assertTrue(callbacks.retries.isEmpty() && callbacks.timedOut.isEmpty())

        session.prepare(PlaybackMode.LIVE)
        scheduler.advance(10.seconds)
        session.prepare(PlaybackMode.LIVE)
        scheduler.advance(10.seconds)
        assertEquals(PlaybackState.PREPARING, state(), "a channel change restarts the prepare timer")
    }
}
