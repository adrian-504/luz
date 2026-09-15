package app.iptvplayer.domain.playback

/**
 * Fast channel switching policy (docs/PLAYBACK.md §4), shared by all platforms. Only tier T0 is used so far: work that
 * opens no stream session (resolving the stream, credentials and headers; warming DNS). T1/T2 need account connection
 * limits and measurements first, because opening a second stream can end the current one on single-connection accounts.
 */
public object PreparationWindow {
    public const val MAX_T0_CANDIDATES: Int = 3

    /**
     * T0 candidates around [currentIndex] of [channels], most likely first: the next channel in the last zap [direction]
     * (+1 or -1; 0 before any zap counts as +1), the channel the other way, then [lastChannel] for the "last channel"
     * toggle. The list wraps around like zapping does; the current channel and duplicates are skipped.
     */
    public fun <T> candidates(channels: List<T>, currentIndex: Int, direction: Int, lastChannel: T?): List<T> {
        if (channels.isEmpty() || currentIndex !in channels.indices) return emptyList()
        val current = channels[currentIndex]
        val step = if (direction < 0) -1 else 1
        val ordered = listOfNotNull(
            channels[(currentIndex + step).mod(channels.size)],
            channels[(currentIndex - step).mod(channels.size)],
            lastChannel,
        )
        return ordered.filter { it != current }.distinct().take(MAX_T0_CANDIDATES)
    }
}

/**
 * Remembers the channel watched before the current one for the "last channel" toggle (REQUIREMENTS.md FR-LIVE-002). Only
 * channels that were actually played count, so zapping quickly past channels does not change the toggle target.
 */
public class ChannelHistory<T : Any> {
    public var current: T? = null
        private set

    public var last: T? = null
        private set

    /** Records that [channel] started playing. */
    public fun played(channel: T) {
        if (channel == current) return
        last = current
        current = channel
    }
}
