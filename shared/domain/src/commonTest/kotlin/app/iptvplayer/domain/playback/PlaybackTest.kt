package app.iptvplayer.domain.playback

import app.iptvplayer.domain.fixtures.StateMachineFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PlaybackTest {
    private fun state(name: String) = PlaybackState.valueOf(name)

    private fun event(name: String) = PlaybackEvent.valueOf(name.uppercase())

    private fun mode(name: String) = PlaybackMode.valueOf(name.uppercase())

    @Test
    fun enumsMatchFixtureVocabulary() {
        assertEquals(StateMachineFixture.states, PlaybackState.entries.map { it.name })
        assertEquals(StateMachineFixture.events, PlaybackEvent.entries.map { it.name.lowercase() })
        assertEquals(StateMachineFixture.modes, PlaybackMode.entries.map { it.name.lowercase() })
        assertEquals(PlaybackState.IDLE, state(StateMachineFixture.initial))
    }

    @Test
    fun everyStateEventModePairMatchesTheAuthoritativeTable() {
        var checked = 0
        for (s in PlaybackState.entries) {
            for (e in PlaybackEvent.entries) {
                for (m in PlaybackMode.entries) {
                    val key = "${s.name}|${e.name.lowercase()}|${m.name.lowercase()}"
                    val expected = StateMachineFixture.transitions.getValue(key)
                    val actual = PlaybackStateMachine.transition(s, e, m)
                    if (expected == "ignore") assertNull(actual, key) else assertEquals(state(expected), actual, key)
                    checked++
                }
            }
        }
        assertEquals(StateMachineFixture.transitions.size, checked)
    }

    @Test
    fun conformanceVectors() {
        for (vector in StateMachineFixture.vectors) {
            var current = state(StateMachineFixture.initial)
            vector.steps.forEachIndexed { index, (eventName, expected) ->
                current = PlaybackStateMachine.next(current, event(eventName), mode(vector.mode))
                assertEquals(state(expected), current, "${vector.name} step $index ($eventName)")
            }
        }
    }

    @Test
    fun liveRetriesBackOffThenStop() {
        val delays = (1..4).map { PlaybackRetryPolicy.delayBeforeRetry(PlaybackErrorCode.TIMEOUT_STALL, PlaybackMode.LIVE, it) }
        assertEquals(listOf(1.seconds, 2.seconds, 4.seconds, null), delays)
    }

    @Test
    fun vodRetriesTwice() {
        val delays = (1..3).map { PlaybackRetryPolicy.delayBeforeRetry(PlaybackErrorCode.HTTP_SERVER, PlaybackMode.VOD, it) }
        assertEquals(listOf(1.seconds, 2.seconds, null), delays)
    }

    @Test
    fun nonRetryableErrorsNeverRetry() {
        for (code in listOf(
            PlaybackErrorCode.HTTP_AUTH,
            PlaybackErrorCode.HTTP_CONNECTION_LIMIT,
            PlaybackErrorCode.SRC_UNSUPPORTED_CODEC,
            PlaybackErrorCode.DRM_FAILED,
        )) {
            assertNull(PlaybackRetryPolicy.delayBeforeRetry(code, PlaybackMode.LIVE, 1), code.name)
        }
    }

    @Test
    fun behindLiveWindowRecoversImmediatelyAndDecoderFailureRetriesOnce() {
        assertEquals(Duration.ZERO, PlaybackRetryPolicy.delayBeforeRetry(PlaybackErrorCode.SRC_BEHIND_LIVE_WINDOW, PlaybackMode.LIVE, 1))
        assertEquals(Duration.ZERO, PlaybackRetryPolicy.delayBeforeRetry(PlaybackErrorCode.DECODER_FAILURE, PlaybackMode.VOD, 1))
        assertNull(PlaybackRetryPolicy.delayBeforeRetry(PlaybackErrorCode.DECODER_FAILURE, PlaybackMode.VOD, 2))
    }

    @Test
    fun timeoutsMatchPlaybackDocument() {
        assertEquals(15.seconds, PlaybackRetryPolicy.prepareTimeout(PlaybackMode.LIVE))
        assertEquals(20.seconds, PlaybackRetryPolicy.prepareTimeout(PlaybackMode.VOD))
        assertEquals(12.seconds, PlaybackRetryPolicy.stallTimeout(PlaybackMode.LIVE))
        assertEquals(30.seconds, PlaybackRetryPolicy.stallTimeout(PlaybackMode.VOD))
    }
}
