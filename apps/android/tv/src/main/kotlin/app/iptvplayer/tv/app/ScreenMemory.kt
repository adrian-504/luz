package app.iptvplayer.tv.app

import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.storage.ChannelRow

/**
 * What the screens last showed, for as long as Luz runs (ADR-0051, Design 1.1): a category's channels come back at once
 * when it is chosen again, instead of placeholder rows while the database is read. Only lists already on screen are
 * kept, a few at a time; a removed source's lists go with it.
 */
class ScreenMemory {
    private val channels = object : LinkedHashMap<String, List<ChannelRow>>(MAX_LISTS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ChannelRow>>?) = size > MAX_LISTS
    }

    @Synchronized
    fun channels(playlistId: PlaylistId, scope: String): List<ChannelRow>? = channels[key(playlistId, scope)]

    @Synchronized
    fun keepChannels(playlistId: PlaylistId, scope: String, rows: List<ChannelRow>) {
        channels[key(playlistId, scope)] = rows
    }

    @Synchronized
    fun forget(playlistId: PlaylistId) {
        channels.keys.removeAll { it.startsWith("${playlistId.value}|") }
    }

    private fun key(playlistId: PlaylistId, scope: String) = "${playlistId.value}|$scope"

    private companion object {
        // "All channels" can be twelve thousand rows; eight lists is generous and still small beside the pictures.
        const val MAX_LISTS = 8
        const val LOAD_FACTOR = 0.75f
    }
}
