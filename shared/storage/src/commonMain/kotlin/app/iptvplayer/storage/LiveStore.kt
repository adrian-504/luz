package app.iptvplayer.storage

import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.live.ChannelFamilies
import app.iptvplayer.domain.live.ChannelNumbers
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.ports.Clock

/**
 * Live TV's own memory (ADR-0052): Luz's channel numbers, the categories it has seen, how each copy of a channel played,
 * and the viewing log. Kept apart from imported rows, so a refresh never touches it; removed with the source.
 */
public class LiveStore(private val content: ContentStore, private val clock: Clock) {
    private val queries get() = content.liveQueries

    public fun channelNumbers(playlistId: PlaylistId): Map<String, ChannelNumbers.Assigned> =
        queries.channelNumbers(playlistId.value).executeAsList().associate {
            it.family_key to ChannelNumbers.Assigned(it.number.toInt(), it.assigned_at)
        }

    public fun saveChannelNumbers(playlistId: PlaylistId, table: Map<String, ChannelNumbers.Assigned>) {
        content.database.transaction {
            queries.clearChannelNumbers(playlistId.value)
            for ((key, assigned) in table) {
                queries.setChannelNumber(playlistId.value, key, assigned.number.toLong(), assigned.seenAt)
            }
        }
    }

    /**
     * Notes the categories in the latest import (x6). The first time, all of them are simply known; after that, a
     * category Luz has not seen is hidden from Live TV and marked new, to wait in Settings. Returns the new ones.
     */
    public fun noteGroups(playlistId: PlaylistId, groupIds: List<String>): List<String> {
        val now = clock.now().toEpochMilliseconds()
        val known = queries.knownGroups(playlistId.value).executeAsList().map { it.group_id }.toHashSet()
        val first = known.isEmpty()
        val added = groupIds.filter { it !in known }
        content.database.transaction {
            for (id in added) {
                queries.addKnownGroup(playlistId.value, id, now, if (first) 0 else 1)
                if (!first) content.hide(playlistId, CustomisationTarget.CHANNEL_GROUP, id)
            }
        }
        return if (first) emptyList() else added
    }

    /** Categories still marked new (x6), for Settings' caption. */
    public fun newGroups(playlistId: PlaylistId): Set<String> =
        queries.knownGroups(playlistId.value).executeAsList().filter { it.is_new != 0L }.map { it.group_id }.toSet()

    public fun clearNewGroups(playlistId: PlaylistId) {
        queries.clearNewGroups(playlistId.value)
    }

    public fun seenGroup(playlistId: PlaylistId, groupId: String) {
        queries.seenGroup(playlistId.value, groupId)
    }

    public fun playback(playlistId: PlaylistId): Map<String, ChannelFamilies.Memory> =
        queries.channelPlayback(playlistId.value).executeAsList().associate {
            it.channel_id to ChannelFamilies.Memory(it.chosen != 0L, it.last_ok_at, it.last_fail_at)
        }

    public fun playedOk(playlistId: PlaylistId, channelId: String, firstFrameMs: Long?) {
        queries.playedOk(playlistId.value, channelId, clock.now().toEpochMilliseconds(), firstFrameMs)
    }

    public fun playedFail(playlistId: PlaylistId, channelId: String) {
        queries.playedFail(playlistId.value, channelId, clock.now().toEpochMilliseconds())
    }

    /** The viewer chose [channelId] among the copies [members] (x9): it wins until they choose another. */
    public fun choose(playlistId: PlaylistId, channelId: String, members: List<String>) {
        content.database.transaction {
            queries.clearChosen(playlistId.value, members)
            queries.choose(playlistId.value, channelId)
        }
    }

    public fun lastPlayedChannel(playlistId: PlaylistId): String? = queries.lastPlayedChannel(playlistId.value).executeAsOneOrNull()

    public fun addSession(playlistId: PlaylistId, kind: String, contentId: String, work: String?, startedAt: Long, seconds: Long) {
        queries.addSession(playlistId.value, kind, contentId, work, startedAt, seconds)
    }

    /** The viewing log keeps 400 days. */
    public fun pruneSessions() {
        queries.pruneSessions(clock.now().toEpochMilliseconds() - SESSION_DAYS * DAY_MS)
    }

    public fun sessionCount(playlistId: PlaylistId): Long = queries.sessionCount(playlistId.value).executeAsOne()

    private companion object {
        const val SESSION_DAYS = 400L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
