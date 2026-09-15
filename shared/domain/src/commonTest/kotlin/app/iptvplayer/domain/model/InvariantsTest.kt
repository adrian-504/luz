package app.iptvplayer.domain.model

import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.domain.ports.TraceName
import app.iptvplayer.domain.ports.TraceNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class InvariantsTest {
    private val start = Instant.fromEpochSeconds(1_789_362_000)
    private val key = EpgChannelKey(EpgSourceId("epg"), "news.example")

    private fun program(end: Instant, title: String = "Example Morning News") = Program(
        id = ProgramId("prog_x"), epgChannelKey = key, start = start, end = end, title = title, subtitle = null,
        description = null, categories = emptyList(), episode = null, artwork = emptyList(), rating = null,
        flags = ProgramFlags(), language = null,
    )

    private fun channel(name: String, sources: List<MediaSourceId>) = Channel(
        id = ChannelId("ch_x"), playlistId = PlaylistId("pl"), groupIds = emptyList(), name = name, number = null,
        logo = null, tvgId = null, providerStreamId = null, languages = emptyList(), countries = emptyList(),
        catchUp = null, mediaSourceIds = sources, isAdult = null, extras = emptyMap(),
        identityHints = IdentityHints(null, null, name),
    )

    @Test
    fun programmesMustHavePositiveDurationAndTitle() {
        assertEquals(60.minutes, program(start + 60.minutes).duration)
        assertFailsWith<IllegalArgumentException> { program(start) }
        assertFailsWith<IllegalArgumentException> { program(start - 1.minutes) }
        assertFailsWith<IllegalArgumentException> { program(start + 1.minutes, title = "  ") }
    }

    @Test
    fun channelsNeedANameAndAMediaSource() {
        channel("Example News", listOf(MediaSourceId("ms_1")))
        assertFailsWith<IllegalArgumentException> { channel(" ", listOf(MediaSourceId("ms_1"))) }
        assertFailsWith<IllegalArgumentException> { channel("Example News", emptyList()) }
        assertFailsWith<IllegalArgumentException> { channel("x".repeat(DomainLimits.MAX_NAME_LENGTH + 1), listOf(MediaSourceId("ms_1"))) }
    }

    @Test
    fun watchCompletionThreshold() {
        assertTrue(WatchState.isComplete(95.minutes, 100.minutes))
        assertFalse(WatchState.isComplete(94.minutes, 100.minutes))
        assertFalse(WatchState.isComplete(0.minutes, 0.minutes))
    }

    @Test
    fun refreshIntervalsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { RefreshPolicy.Interval(0) }
    }

    @Test
    fun domainErrorsHaveStableCodesAndRetryability() {
        assertEquals("NET_DNS", DomainError.Network(NetworkErrorKind.DNS).code)
        assertEquals("error.net_dns", DomainError.Network(NetworkErrorKind.DNS).messageKey)
        assertFalse(DomainError.Network(NetworkErrorKind.TLS).retryable)
        assertTrue(DomainError.Http(503).retryable)
        assertTrue(DomainError.Http(429).retryable)
        assertFalse(DomainError.Http(404).retryable)
        assertFalse(DomainError.Auth(AuthFailure.INVALID_CREDENTIALS).retryable)
        assertEquals("CANCELLED", DomainError.Cancelled.code)
    }

    @Test
    fun traceNamesCannotCarryFreeText() {
        assertEquals("playback.first_frame", TraceNames.PLAYBACK_FIRST_FRAME.value)
        assertFailsWith<IllegalArgumentException> { TraceName("http://provider.example.com/live") }
        assertFailsWith<IllegalArgumentException> { TraceName("Example Channel") }
        assertFailsWith<IllegalArgumentException> { TraceName("") }
    }
}
