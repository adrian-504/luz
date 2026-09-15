package app.iptvplayer.platform.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import app.iptvplayer.domain.playback.PlaybackErrorCode
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps Media3 failures to the shared taxonomy (docs/PLAYBACK.md §5). Inputs are plain values so the mapping is tested on
 * the JVM without a player.
 */
@OptIn(UnstableApi::class)
object Media3ErrorMapper {
    fun map(errorCode: Int, httpStatus: Int?, causes: List<Throwable>, networkAvailable: Boolean = true): PlaybackErrorCode {
        when {
            // A live playlist that stops advancing is a stall; one whose media sequence jumps backwards is a restarted stream.
            causes.any { it is HlsPlaylistTracker.PlaylistStuckException } -> return PlaybackErrorCode.TIMEOUT_STALL
            causes.any { it is HlsPlaylistTracker.PlaylistResetException } -> return PlaybackErrorCode.SRC_ENDED_UNEXPECTEDLY
            causes.any { it is UnknownHostException } ->
                return if (networkAvailable) PlaybackErrorCode.NET_DNS else PlaybackErrorCode.NET_OFFLINE
            causes.any { it is SSLException } -> return PlaybackErrorCode.NET_TLS
            causes.any { it is SocketTimeoutException } -> return PlaybackErrorCode.NET_TIMEOUT
        }
        return when (errorCode) {
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> PlaybackErrorCode.SRC_BEHIND_LIVE_WINDOW
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> httpStatus(httpStatus)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                if (networkAvailable) PlaybackErrorCode.NET_TIMEOUT else PlaybackErrorCode.NET_OFFLINE
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackErrorCode.NET_TIMEOUT
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> PlaybackErrorCode.SRC_UNSUPPORTED_PROTOCOL
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackErrorCode.HTTP_NOT_FOUND
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> PlaybackErrorCode.SRC_UNSUPPORTED_CONTAINER
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            -> PlaybackErrorCode.SRC_MANIFEST_MALFORMED
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            -> PlaybackErrorCode.SRC_UNSUPPORTED_CONTAINER
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> PlaybackErrorCode.SRC_UNSUPPORTED_CODEC
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            -> PlaybackErrorCode.DECODER_FAILURE
            in DRM_CODES -> PlaybackErrorCode.DRM_FAILED
            else -> PlaybackErrorCode.UNKNOWN
        }
    }

    private fun httpStatus(status: Int?): PlaybackErrorCode = when (status) {
        401, 403 -> PlaybackErrorCode.HTTP_AUTH
        404, 410 -> PlaybackErrorCode.HTTP_NOT_FOUND
        // Panels report an exceeded max_connections differently; 403 stays HTTP_AUTH until account state is available.
        458, 509 -> PlaybackErrorCode.HTTP_CONNECTION_LIMIT
        null -> PlaybackErrorCode.UNKNOWN
        else -> if (status in 500..599) PlaybackErrorCode.HTTP_SERVER else PlaybackErrorCode.UNKNOWN
    }

    private val DRM_CODES = PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
}
