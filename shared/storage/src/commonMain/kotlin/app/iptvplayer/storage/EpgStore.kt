package app.iptvplayer.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.storage.db.IptvDatabase
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A programme row as returned by guide window queries. */
public data class GuideRow(
    public val id: ProgramId,
    public val channel: EpgChannelKey,
    public val start: Instant,
    public val end: Instant,
    public val title: String,
)

public data class ChannelEpgLinkRow(
    public val channelId: String,
    public val epgSourceId: String,
    public val epgChannelId: String,
    public val method: String,
    public val confidence: Int,
)

public data class GuideProgramme(public val title: String, public val start: Instant, public val end: Instant)

public data class NowNextRow(public val current: GuideProgramme?, public val next: GuideProgramme?)

/** Minimal programme input for writing a snapshot. */
public data class ProgramRow(
    public val id: ProgramId,
    public val channelId: String,
    public val start: Instant,
    public val end: Instant,
    public val title: String,
    public val subtitle: String?,
    public val description: String?,
)

/**
 * SPIKE (ADR-0013): guide storage on SQLite through SQLDelight — snapshot writes in batched transactions, atomic
 * activation, bounded window queries and FTS5 search over titles/descriptions. Not yet wired into an import pipeline.
 */
public class EpgStore(private val driver: SqlDriver) {
    private val database = IptvDatabase(driver)
    private val queries = database.epgQueries

    /** Creates the FTS5 table when the platform SQLite supports it; returns false otherwise (fallback needed). */
    public fun enableSearch(): Boolean = try {
        driver.execute(
            null,
            "CREATE VIRTUAL TABLE IF NOT EXISTS epg_program_search USING fts5(title, description, content='', contentless_delete=1, tokenize='unicode61 remove_diacritics 2')",
            0,
        )
        true
    } catch (_: Exception) {
        false
    }

    /** Writes [programmes] as snapshot [snapshot] of [source] in transactions of [batchSize] rows. */
    public fun writeSnapshot(source: EpgSourceId, snapshot: Long, programmes: Sequence<ProgramRow>, batchSize: Int = 1_000): Int {
        val writer = beginSnapshot(source, snapshot, batchSize)
        programmes.forEach { writer.add(it) }
        return writer.finish()
    }

    /** Push-style writer for importers that emit programmes one at a time. Rows stay invisible until [activate]. */
    public fun beginSnapshot(source: EpgSourceId, snapshot: Long, batchSize: Int = 1_000): ProgramWriter =
        ProgramWriter(source, snapshot, batchSize)

    public inner class ProgramWriter internal constructor(
        private val source: EpgSourceId,
        private val snapshot: Long,
        private val batchSize: Int,
    ) {
        private val pending = ArrayList<ProgramRow>(batchSize)
        private var written = 0

        public fun add(programme: ProgramRow) {
            pending += programme
            if (pending.size >= batchSize) flush()
        }

        /** Writes the remaining rows and returns the total written. */
        public fun finish(): Int {
            flush()
            return written
        }

        private fun flush() {
            if (pending.isEmpty()) return
            database.transaction {
                for (p in pending) {
                    queries.insertProgram(
                        source.value, snapshot, p.id.value, p.channelId,
                        p.start.epochSeconds, p.end.epochSeconds, p.title, p.subtitle, p.description,
                    )
                }
            }
            written += pending.size
            pending.clear()
        }
    }

    /** Replaces the channel ↔ EPG links of [playlistId]. */
    public fun replaceLinks(playlistId: String, links: List<ChannelEpgLinkRow>) {
        database.transaction {
            queries.deleteLinks(playlistId)
            links.forEach {
                queries.insertLink(
                    playlistId,
                    it.channelId,
                    it.epgSourceId,
                    it.epgChannelId,
                    it.method,
                    it.confidence.toLong(),
                )
            }
        }
    }

    public fun linkCount(playlistId: String): Long = queries.linkCount(playlistId).executeAsOne()

    /** Programmes of linked channels overlapping [from]..[until], per channel in start order, for the guide grid. */
    public fun programmes(
        playlistId: String,
        channelIds: Collection<String>,
        from: Instant,
        until: Instant,
    ): Map<String, List<GuideProgramme>> {
        if (channelIds.isEmpty()) return emptyMap()
        return queries.guideForChannels(playlistId, channelIds, (from - 24.hours).epochSeconds, until.epochSeconds, from.epochSeconds)
            .executeAsList()
            .groupBy(
                { it.channel_id },
                { GuideProgramme(it.title, Instant.fromEpochSeconds(it.start_utc), Instant.fromEpochSeconds(it.end_utc)) },
            )
    }

    /**
     * Current and next programme per linked channel at [now] (docs/EPG.md §4), for the channel list. Channels without a
     * link or without programmes are absent from the result.
     */
    public fun nowNext(playlistId: String, channelIds: Collection<String>, now: Instant): Map<String, NowNextRow> {
        if (channelIds.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, NowNextRow>()
        val rows = queries.guideForChannels(
            playlistId,
            channelIds,
            (now - 24.hours).epochSeconds,
            (now + 12.hours).epochSeconds,
            now.epochSeconds,
        ).executeAsList()
        for ((channel, programmes) in rows.groupBy { it.channel_id }) {
            val current = programmes.firstOrNull { it.start_utc <= now.epochSeconds && it.end_utc > now.epochSeconds }
            val next = programmes.firstOrNull { it.start_utc > now.epochSeconds }
            result[channel] = NowNextRow(
                current?.let { GuideProgramme(it.title, Instant.fromEpochSeconds(it.start_utc), Instant.fromEpochSeconds(it.end_utc)) },
                next?.let { GuideProgramme(it.title, Instant.fromEpochSeconds(it.start_utc), Instant.fromEpochSeconds(it.end_utc)) },
            )
        }
        return result
    }

    /** Builds the full-text index for a written snapshot in one statement (the INDEX pipeline stage). */
    public fun indexSearch(source: EpgSourceId, snapshot: Long) {
        driver.execute(
            null,
            "INSERT INTO epg_program_search(rowid, title, description) SELECT row_id, title, description FROM epg_program WHERE source_id = ? AND snapshot = ?",
            2,
        ) {
            bindString(0, source.value)
            bindLong(1, snapshot)
        }
    }

    /** Makes [snapshot] the one readers see, then deletes the previous snapshot's rows. */
    public fun activate(source: EpgSourceId, snapshot: Long) {
        val previous = queries.activeSnapshot(source.value).executeAsOneOrNull()
        queries.activate(source.value, snapshot)
        if (previous != null && previous != snapshot) {
            database.transaction {
                if (searchEnabled()) {
                    driver.execute(
                        null,
                        "DELETE FROM epg_program_search WHERE rowid IN (SELECT row_id FROM epg_program WHERE source_id = ? AND snapshot = ?)",
                        2,
                    ) {
                        bindString(0, source.value)
                        bindLong(1, previous)
                    }
                }
                queries.deleteSnapshot(source.value, previous)
            }
        }
    }

    /** Deletes an unpublished snapshot, for example after a failed import. */
    public fun discard(source: EpgSourceId, snapshot: Long) {
        queries.deleteSnapshot(source.value, snapshot)
    }

    public fun activeSnapshot(source: EpgSourceId): Long? = queries.activeSnapshot(source.value).executeAsOneOrNull()

    public fun count(source: EpgSourceId, snapshot: Long): Long = queries.countSnapshot(source.value, snapshot).executeAsOne()

    /** Programmes of [channels] intersecting [start]..[end] in the active snapshot. */
    public fun window(source: EpgSourceId, channels: Collection<String>, start: Instant, end: Instant): List<GuideRow> {
        val snapshot = activeSnapshot(source) ?: return emptyList()
        return queries.window(
            source.value,
            snapshot,
            channels,
            (start - 24.hours).epochSeconds,
            end.epochSeconds,
            start.epochSeconds,
        ) { id, channel, s, e, title ->
            GuideRow(ProgramId(id), EpgChannelKey(source, channel), Instant.fromEpochSeconds(s), Instant.fromEpochSeconds(e), title)
        }.executeAsList()
    }

    /** Full-text search over titles and descriptions of the active snapshot. [query] is an FTS5 expression. */
    public fun search(source: EpgSourceId, query: String, limit: Long = 50, ranked: Boolean = true): List<String> {
        val snapshot = activeSnapshot(source) ?: return emptyList()
        return driver.executeQuery(
            null,
            // CROSS JOIN pins the join order: the FTS index drives and rows are fetched by primary key. With a plain JOIN the
            // planner may scan every programme of the snapshot and probe the full-text index per row (minutes at 1M rows).
            SEARCH_SQL_PREFIX +
                "WHERE epg_program_search MATCH ? AND p.source_id = ? AND p.snapshot = ? " + (if (ranked) "ORDER BY rank " else "") +
                "LIMIT ?",
            { cursor ->
                val out = ArrayList<String>()
                while (cursor.next().value) out += cursor.getString(0)!!
                QueryResult.Value(out)
            },
            4,
        ) {
            bindString(0, query)
            bindString(1, source.value)
            bindLong(2, snapshot)
            bindLong(3, limit)
        }.value
    }

    internal companion object {
        const val SEARCH_SQL_PREFIX: String = "SELECT p.title FROM epg_program_search s CROSS JOIN epg_program p ON p.row_id = s.rowid "
    }

    private fun searchEnabled(): Boolean = driver.executeQuery(
        null,
        "SELECT count(*) FROM sqlite_master WHERE name = 'epg_program_search'",
        { cursor -> QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
        0,
    ).value
}
