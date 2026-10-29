package app.iptvplayer.tv.app

import android.os.SystemClock
import android.util.Log
import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.live.ChannelFamilies
import app.iptvplayer.domain.live.ChannelNumbers
import app.iptvplayer.domain.live.NewsChannels
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.storage.ChannelRow
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.LiveStore
import java.util.concurrent.ConcurrentHashMap

/**
 * One channel from many copies, with numbers that stay (ADR-0052). Built once per source and channel import, off the
 * main thread (every call here blocks), and rebuilt when the copies' playing record changes. Lists pass through
 * [collapse] on their way to the screens, so FHD, HD and SD of one channel show as one row with Luz's number.
 */
class LiveChannels(private val content: ContentStore, private val live: LiveStore, private val now: () -> Long) {
    private class Built(
        val snapshot: Long?,
        val families: ChannelFamilies.Families,
        /** Luz's number for each family. */
        val numbers: Map<String, Int>,
    )

    private val built = ConcurrentHashMap<PlaylistId, Built>()
    private val news = ConcurrentHashMap<PlaylistId, Pair<Long?, Map<NewsChannels.Language, List<String>>>>()

    /** Forget what was built for [playlistId]: a new import, or a copy that just played or failed. */
    fun forget(playlistId: PlaylistId) {
        built.remove(playlistId)
    }

    private fun built(playlistId: PlaylistId): Built {
        val snapshot = content.unitState(playlistId, ImportUnit.LIVE)?.activeSnapshot
        built[playlistId]?.takeIf { it.snapshot == snapshot }?.let { return it }
        val started = SystemClock.elapsedRealtime()
        val all = content.channels(playlistId, null)
        val families = ChannelFamilies.build(
            all.mapIndexed { index, row -> ChannelFamilies.Copy(row.id.value, row.familyKey, row.tvgId, row.quality, index) },
            live.playback(playlistId),
            now(),
        )
        val numbers = live.channelNumbers(playlistId).mapValues { it.value.number }
        // The Bbox go/no-go (ADR-0052): building must stay under 150 ms on the owner's channels. Counts and time only.
        Log.i(LOG_TAG, "families: ${all.size} copies, ${families.members.size} channels in ${SystemClock.elapsedRealtime() - started} ms")
        return Built(snapshot, families, numbers).also { built[playlistId] = it }
    }

    /**
     * One row per channel, at its first place in [rows], showing the best copy the list holds, with Luz's number and the
     * favourite mark if any copy carries it.
     */
    fun collapse(playlistId: PlaylistId, rows: List<ChannelRow>): List<ChannelRow> {
        if (rows.isEmpty()) return rows
        val built = built(playlistId)
        val byId = rows.associateBy { it.id.value }
        val favourites = content.favoriteChannels(playlistId).mapNotNull { built.families.familyOf[it.id.value] }.toHashSet()
        return built.families.collapse(rows.map { it.id.value }).map { id ->
            val row = byId.getValue(id)
            val family = built.families.familyOf[id] ?: return@map row
            row.copy(number = built.numbers[family] ?: row.number, isFavorite = row.isFavorite || family in favourites)
        }
    }

    /** The copies of [channelId]'s channel, best first; just [channelId] when it has no others. */
    fun copies(playlistId: PlaylistId, channelId: ChannelId): List<ChannelId> {
        val built = built(playlistId)
        val family = built.families.familyOf[channelId.value] ?: return listOf(channelId)
        return built.families.members.getValue(family).map(::ChannelId)
    }

    /**
     * The News tab (s0): news channels by language, most watched first, one row per channel. A channel is judged by its
     * name, or by the name of a category it is in; worked out once per channel import (it reads every channel).
     */
    fun news(playlistId: PlaylistId): Map<NewsChannels.Language, List<ChannelRow>> {
        val snapshot = content.unitState(playlistId, ImportUnit.LIVE)?.activeSnapshot
        val ids = news[playlistId]?.takeIf { it.first == snapshot }?.second ?: run {
            val watched = watchedRank(playlistId)
            val byName = content.channels(playlistId, null).map {
                NewsChannels.Candidate(it.id.value, it.name, null, watched[it.id.value] ?: 0)
            }
            // A channel in a category named for news is judged with that name too (its language may be in it).
            val inGroups = content.groups(playlistId).flatMap { group ->
                if (!NewsChannels.isNewsWord(group.title)) return@flatMap emptyList()
                content.channels(playlistId, group.id).map {
                    NewsChannels.Candidate(
                        it.id.value,
                        it.name,
                        group.title,
                        watched[it.id.value] ?: 0,
                    )
                }
            }
            val candidates = (inGroups + byName).distinctBy { it.id }
            NewsChannels.classify(candidates).also { news[playlistId] = snapshot to it }
        }
        val all = ids.values.flatten()
        val rows = collapse(
            playlistId,
            content.channelsByIds(playlistId, all).let { found ->
                val byId = found.associateBy { it.id.value }
                all.mapNotNull { byId[it] }
            },
        ).associateBy { it.id.value }
        return ids.mapValues { (_, list) -> list.mapNotNull { rows[it] }.distinctBy { it.id } }.filterValues { it.isNotEmpty() }
    }

    private fun watchedRank(playlistId: PlaylistId): Map<String, Int> {
        val ids = live.recentChannels(playlistId, WATCHED_FOR_NEWS)
        return ids.withIndex().associate { (index, id) -> id to (ids.size - index) }
    }

    /** Gives every channel its number after an import: kept from before, new ones after the last (x5). */
    fun assignNumbers(playlistId: PlaylistId) {
        forget(playlistId)
        val built = built(playlistId)
        val all = content.channels(playlistId, null).associateBy { it.id.value }
        val families = built.families.members.map { (family, copies) -> family to all[copies.first()]?.number }
        val table = ChannelNumbers.assign(live.channelNumbers(playlistId), families, now())
        live.saveChannelNumbers(playlistId, table)
        forget(playlistId)
    }

    private companion object {
        const val LOG_TAG = "IptvImport"
        const val WATCHED_FOR_NEWS = 200
    }
}
