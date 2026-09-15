package app.iptvplayer.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.storage.db.IptvDatabase
import java.io.File
import java.util.Properties
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

/**
 * ADR-0013 storage spike on the JVM host with a file-backed SQLite database (WAL). Numbers are printed for the ADR
 * record; they are informational, not device gates (docs/PERFORMANCE.md).
 */
class EpgStoreSpikeTest {
    private val directory: File = createTempDirectory("epg-spike").toFile()
    private val source = EpgSourceId("spike")
    private val base = Instant.parse("2026-09-14T00:00:00Z")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun driver(name: String): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${File(directory, name).absolutePath}", Properties(), IptvDatabase.Schema)
        driver.executeQuery(null, "PRAGMA journal_mode=WAL", { QueryResult.Value(Unit) }, 0)
        driver.execute(null, "PRAGMA synchronous=NORMAL", 0)
        return driver
    }

    private fun programmes(channels: Int, perChannel: Int, snapshot: Long): Sequence<ProgramRow> = sequence {
        for (c in 0 until channels) {
            var start = base
            for (p in 0 until perChannel) {
                val end = start + (30 + (p % 4) * 15).minutes
                yield(
                    ProgramRow(
                        ProgramId("prog_${snapshot}_${c}_$p"),
                        "ch$c.example",
                        start,
                        end,
                        if (p % 97 == 0) "Example Football Live $p" else "Programme $p",
                        null,
                        "Synthetic description with café and details for channel $c",
                    ),
                )
                start = end
            }
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double = values.sorted()[((values.size - 1) * p).toInt()]

    @Test
    fun fts5IsAvailableInTheJvmSqliteBuild() {
        val driver = driver("fts.db")
        val version = driver.executeQuery(null, "SELECT sqlite_version()", {
            QueryResult.Value(it.next().value.let { _ -> it.getString(0) })
        }, 0).value
        val fts5 = driver.executeQuery(null, "SELECT sqlite_compileoption_used('ENABLE_FTS5')", {
            QueryResult.Value(it.next().value.let { _ -> it.getLong(0) })
        }, 0).value
        println("Storage spike: JVM sqlite-jdbc SQLite $version, ENABLE_FTS5=$fts5")
        assertTrue(EpgStore(driver).enableSearch())
        driver.close()
    }

    @Test
    fun snapshotWriteActivateQueryAndSearch() {
        val driver = driver("small.db")
        val store = EpgStore(driver)
        assertTrue(store.enableSearch())
        assertEquals(40, store.writeSnapshot(source, 1, programmes(4, 10, 1)))
        store.indexSearch(source, 1)
        store.activate(source, 1)

        val rows = store.window(source, listOf("ch1.example", "ch2.example"), base + 1.hours, base + 3.hours)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.end > base + 1.hours && it.start < base + 3.hours })
        assertEquals(rows.sortedWith(compareBy({ it.channel.channelId }, { it.start })), rows)
        assertTrue(store.search(source, "football").all { it.startsWith("Example Football") })
        assertEquals(4, store.search(source, "football").size)
        assertEquals(40, store.search(source, "cafe").size, "diacritics removed by the unicode61 tokenizer")

        // A new snapshot is invisible until activated, then replaces the old one atomically.
        store.writeSnapshot(source, 2, programmes(2, 5, 2))
        store.indexSearch(source, 2)
        assertEquals(4, store.search(source, "football").size)
        store.activate(source, 2)
        assertEquals(0, store.count(source, 1))
        assertEquals(10, store.count(source, 2))
        assertEquals(2, store.search(source, "football").size)

        val plan = driver.executeQuery(
            null,
            "EXPLAIN QUERY PLAN " + EpgStore.SEARCH_SQL_PREFIX +
                "WHERE epg_program_search MATCH 'x' AND p.source_id = 's' AND p.snapshot = 1 LIMIT 5",
            { cursor ->
                val details = ArrayList<String>()
                while (cursor.next().value) details += cursor.getString(3)!!
                QueryResult.Value(details)
            },
            0,
        ).value
        assertTrue(plan.first().contains("VIRTUAL TABLE"), "the full-text index must drive the search join: $plan")
        driver.close()
    }

    @Test
    fun oneMillionProgrammesBenchmark() {
        val log = File("build/storage-spike-progress.log").apply {
            parentFile.mkdirs()
            writeText("")
        }
        fun step(label: String) = log.appendText("${java.time.LocalTime.now()} $label\n")
        val driver = driver("large.db")
        val store = EpgStore(driver)
        store.enableSearch()

        step("write start")
        val (written, writeTime) = measureTimedValue { store.writeSnapshot(source, 1, programmes(2_000, 500, 1), batchSize = 5_000) }
        assertEquals(1_000_000, written)
        step("write done $writeTime")
        val indexTime = measureTime { store.indexSearch(source, 1) }
        step("fts index done $indexTime")
        store.activate(source, 1)

        val windowTimes = ArrayList<Double>()
        repeat(200) { i ->
            val channels = (0 until 50).map { "ch${(i * 37 + it) % 2_000}.example" }
            val start = base + (i % 18).hours
            windowTimes +=
                measureTime { assertTrue(store.window(source, channels, start, start + 6.hours).isNotEmpty()) }.inWholeMicroseconds / 1000.0
        }
        val nowNextTimes = ArrayList<Double>()
        repeat(200) { i ->
            val channels = (0 until 20).map { "ch${(i * 13 + it) % 2_000}.example" }
            val now = base + (i % 40).hours
            nowNextTimes += measureTime { store.window(source, channels, now, now + 4.hours) }.inWholeMicroseconds / 1000.0
        }
        step("window queries done")
        val targetedSearch = ArrayList<Double>()
        repeat(20) { targetedSearch += measureTime { store.search(source, "football", limit = 50) }.inWholeMicroseconds / 1000.0 }
        step("targeted search done")
        val broadRanked = measureTime { store.search(source, "programme", limit = 50, ranked = true) }
        step("broad ranked search done $broadRanked")
        val broadUnranked = measureTime { store.search(source, "programme", limit = 50, ranked = false) }
        step("broad unranked search done $broadUnranked")

        val replaceTime = measureTime {
            store.writeSnapshot(source, 2, programmes(200, 500, 2), batchSize = 5_000)
            store.indexSearch(source, 2)
            store.activate(source, 2)
        }
        step("snapshot replace done $replaceTime")
        val fileMiB = File(directory, "large.db").length() / (1024 * 1024)
        println(
            "Storage spike (JVM host, informational): insert 1,000,000 programmes in $writeTime; FTS index build $indexTime; " +
                "window 50ch x 6h P50=%.2f ms P95=%.2f ms; now/next 20ch P95=%.2f ms; targeted search P95=%.2f ms; ".format(
                    percentile(windowTimes, 0.5),
                    percentile(windowTimes, 0.95),
                    percentile(nowNextTimes, 0.95),
                    percentile(targetedSearch, 0.95),
                ) + "broad search (~1M matches) ranked $broadRanked, unranked $broadUnranked; " +
                "snapshot replace (100k new, 1M deleted) $replaceTime; database ~$fileMiB MiB",
        )
        assertEquals(100_000, store.count(source, 2))
        driver.close()
    }
}
