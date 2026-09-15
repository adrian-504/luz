package app.iptvplayer.platform.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Media3 retries failed loads a few times before reporting an error, which is right for brief network blips but only
 * delays a definitive answer: client errors (401, 403, 404, 410, …) fail immediately so the user sees the reason and the
 * shared recovery policy decides what happens next (docs/PLAYBACK.md §3). 408 and 429 stay retryable.
 */
@OptIn(UnstableApi::class)
internal class PlaybackLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val status = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (status != null && status in 400..499 && status != 408 && status != 429) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }
}
