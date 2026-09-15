package app.iptvplayer.platform.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import app.iptvplayer.domain.playback.PlaybackErrorCode
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(UnstableApi::class)
class Media3ErrorMapperTest {
    private fun map(code: Int, status: Int? = null, causes: List<Throwable> = emptyList(), online: Boolean = true) =
        Media3ErrorMapper.map(code, status, causes, online)

    @Test
    fun httpStatuses() {
        val bad = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        assertEquals(PlaybackErrorCode.HTTP_AUTH, map(bad, 401))
        assertEquals(PlaybackErrorCode.HTTP_AUTH, map(bad, 403))
        assertEquals(PlaybackErrorCode.HTTP_NOT_FOUND, map(bad, 404))
        assertEquals(PlaybackErrorCode.HTTP_NOT_FOUND, map(bad, 410))
        assertEquals(PlaybackErrorCode.HTTP_CONNECTION_LIMIT, map(bad, 458))
        assertEquals(PlaybackErrorCode.HTTP_CONNECTION_LIMIT, map(bad, 509))
        assertEquals(PlaybackErrorCode.HTTP_SERVER, map(bad, 503))
        assertEquals(PlaybackErrorCode.UNKNOWN, map(bad, 418))
    }

    @Test
    fun networkCausesWinOverTheEngineCode() {
        val io = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        assertEquals(
            PlaybackErrorCode.NET_DNS,
            map(io, causes = listOf(IOException(UnknownHostException("stream.invalid")), UnknownHostException("stream.invalid"))),
        )
        assertEquals(PlaybackErrorCode.NET_OFFLINE, map(io, causes = listOf(UnknownHostException("x")), online = false))
        assertEquals(PlaybackErrorCode.NET_TLS, map(io, causes = listOf(SSLHandshakeException("bad certificate"))))
        assertEquals(
            PlaybackErrorCode.NET_TIMEOUT,
            map(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, causes = listOf(SocketTimeoutException())),
        )
        assertEquals(PlaybackErrorCode.NET_TIMEOUT, map(io))
        assertEquals(PlaybackErrorCode.NET_OFFLINE, map(io, online = false))
    }

    @Test
    fun sourceDecoderAndDrmFailures() {
        assertEquals(PlaybackErrorCode.SRC_MANIFEST_MALFORMED, map(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
        assertEquals(PlaybackErrorCode.SRC_UNSUPPORTED_CONTAINER, map(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertEquals(PlaybackErrorCode.SRC_UNSUPPORTED_CODEC, map(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(PlaybackErrorCode.DECODER_FAILURE, map(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackErrorCode.SRC_BEHIND_LIVE_WINDOW, map(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
        assertEquals(PlaybackErrorCode.DRM_FAILED, map(PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED))
        assertEquals(PlaybackErrorCode.SRC_UNSUPPORTED_PROTOCOL, map(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED))
        assertEquals(PlaybackErrorCode.UNKNOWN, map(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun liveHlsPlaylistProblemsAreRecoverable() {
        val io = PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        val stuck = map(io, causes = listOf(HlsPlaylistTracker.PlaylistStuckException(android.net.Uri.EMPTY)))
        assertEquals(PlaybackErrorCode.TIMEOUT_STALL, stuck)
        assertEquals(
            PlaybackErrorCode.SRC_ENDED_UNEXPECTEDLY,
            map(io, causes = listOf(HlsPlaylistTracker.PlaylistResetException(android.net.Uri.EMPTY))),
        )
        assertTrue(stuck.retryable)
    }
}
