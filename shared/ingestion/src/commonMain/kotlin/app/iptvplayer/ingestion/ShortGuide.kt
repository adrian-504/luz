package app.iptvplayer.ingestion

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.protocols.xtream.XtreamClient
import app.iptvplayer.protocols.xtream.XtreamCredentials
import app.iptvplayer.protocols.xtream.XtreamEndpoint
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.GuideProgramme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** What one short-guide call did, for device diagnostics (numbers and error codes only). */
public data class ShortGuideReport(
    public val requested: Int,
    public val fromCache: Int,
    public val fetched: Int,
    public val withProgrammes: Int,
    public val programmes: Int,
    public val errors: Map<String, Int>,
    public val skipped: String? = null,
)

/**
 * Xtream `get_short_epg` fallback (EPG.md §1, IPTV_PROTOCOLS.md §4) for providers whose XMLTV guide is empty: the next few
 * programmes of one channel, fetched only for channels the user is looking at and kept in memory. Requests are API calls,
 * not stream sessions, so they do not count against connection limits; still at most [MAX_PARALLEL] run at once and
 * [MAX_CHANNELS_PER_CALL] per call, and failures are remembered so scrolling does not repeat them.
 */
internal class ShortGuide(
    private val xtream: XtreamClient,
    private val content: ContentStore,
    private val secrets: SecretStore,
    private val clock: Clock,
) {
    private class Entry(val programmes: List<GuideProgramme>, val fetchedAt: Instant)

    private val cache = LinkedHashMap<String, Entry>()
    private val lock = Mutex()
    private val permits = Semaphore(MAX_PARALLEL)

    suspend fun programmes(
        playlistId: PlaylistId,
        channelIds: List<ChannelId>,
        report: (ShortGuideReport) -> Unit = {},
    ): Map<String, List<GuideProgramme>> {
        fun skip(reason: String): Map<String, List<GuideProgramme>> {
            report(ShortGuideReport(channelIds.size, 0, 0, 0, 0, emptyMap(), reason))
            return emptyMap()
        }
        val source = content.source(playlistId) ?: return skip("NO_SOURCE")
        if (source.type != PlaylistType.XTREAM) return skip("NOT_XTREAM")
        val endpoint = source.sourceTemplate?.let { XtreamEndpoint.parse(it.template) } ?: return skip("NO_ENDPOINT")
        val bundle = source.credentialRef?.let { secrets.get(it) } ?: return skip("NO_CREDENTIALS")
        val credentials = XtreamCredentials(
            bundle.username ?: return skip("NO_CREDENTIALS"),
            bundle.password ?: return skip("NO_CREDENTIALS"),
        )
        var fromCache = 0
        var fetched = 0
        val errors = LinkedHashMap<String, Int>()
        val now = clock.now()
        val result = LinkedHashMap<String, List<GuideProgramme>>()
        for (channelId in channelIds.distinct().take(MAX_CHANNELS_PER_CALL)) {
            val key = "${playlistId.value}/${channelId.value}"
            val cached = lock.withLock { cache[key] }
            if (cached != null && isFresh(cached, now)) {
                result[channelId.value] = cached.programmes
                fromCache++
                continue
            }
            val locator = content.mediaSources(playlistId, channelId).firstNotNullOfOrNull { it.locator as? MediaLocator.XtreamStream }
                ?: continue
            val epgKey = EpgChannelKey(EpgSourceId("short:${playlistId.value}"), locator.streamId)
            val (programmes, error) = permits.withPermit {
                xtream.shortEpg(endpoint, credentials, locator.streamId, epgKey, limit = PROGRAMMES_PER_CHANNEL)
            }
            fetched++
            if (error != null) errors[error.code] = (errors[error.code] ?: 0) + 1
            val rows = if (error != null) emptyList() else programmes.map { GuideProgramme(it.title, it.start, it.end, it.description) }
            lock.withLock {
                cache.remove(key)
                cache[key] = Entry(rows, now)
                while (cache.size > MAX_CACHED_CHANNELS) cache.remove(cache.keys.first())
            }
            result[channelId.value] = rows
        }
        val fetchedResults = result.values
        report(
            ShortGuideReport(
                requested = channelIds.size,
                fromCache = fromCache,
                fetched = fetched,
                withProgrammes = fetchedResults.count { it.isNotEmpty() },
                programmes = fetchedResults.sumOf { it.size },
                errors = errors,
                skipped = if (result.isEmpty() && fetched == 0 && fromCache == 0) "NO_XTREAM_STREAMS" else null,
            ),
        )
        return result
    }

    /** A result stays valid until its last programme ends (at most [MAX_AGE]); empty results are retried after [EMPTY_AGE]. */
    private fun isFresh(entry: Entry, now: Instant): Boolean {
        val age = now - entry.fetchedAt
        val last = entry.programmes.maxOfOrNull { it.end }
        return if (last == null) age < EMPTY_AGE else age < MAX_AGE && now < last
    }

    companion object {
        const val MAX_CHANNELS_PER_CALL = 20
        const val MAX_PARALLEL = 3
        const val PROGRAMMES_PER_CHANNEL = 10
        const val MAX_CACHED_CHANNELS = 500
        val MAX_AGE = 30.minutes
        val EMPTY_AGE = 10.minutes
    }
}
