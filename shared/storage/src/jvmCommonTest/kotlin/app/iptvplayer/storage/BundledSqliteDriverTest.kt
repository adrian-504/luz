package app.iptvplayer.storage

import app.cash.sqldelight.db.QueryResult
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.storage.db.IptvDatabase
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The bundled-SQLite driver (ADR-0025) on the JVM and, through the shared test tree, on Android devices: SQLite version and
 * FTS5 features, the guide store end to end, transactions, reopening and concurrent writers.
 */
class BundledSqliteDriverTest {
    private val file: File = File.createTempFile("bundled-driver", ".db").also { it.delete() }
    private val source = EpgSourceId("bundled")
    private val base = Instant.parse("2026-09-14T00:00:00Z")

    @AfterTest
    fun cleanUp() {
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun open() = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)

    private fun BundledSqliteDriver.single(sql: String): String? =
        executeQuery(null, sql, { QueryResult.Value(if (it.next().value) it.getString(0) else null) }, 0).value

    private fun programmes(channels: Int, perChannel: Int, snapshot: Long) = sequence {
        for (c in 0 until channels) {
            for (p in 0 until perChannel) {
                val start = base + (p * 30).minutes
                yield(
                    ProgramRow(
                        ProgramId("p${snapshot}_${c}_$p"),
                        "ch$c",
                        start,
                        start + 30.minutes,
                        if (p % 5 ==
                            0
                        ) {
                            "Café Football $p"
                        } else {
                            "News $p"
                        },
                        null,
                        null,
                    ),
                )
            }
        }
    }

    @Test
    fun bundledSqliteHasTheSearchFeaturesTheStoreNeeds() {
        val driver = open()
        val version = driver.single("SELECT sqlite_version()")!!
        assertTrue(version >= "3.43", "contentless_delete needs SQLite 3.43+, got $version")
        assertEquals("1", driver.single("SELECT sqlite_compileoption_used('ENABLE_FTS5')"))
        assertEquals("wal", driver.single("PRAGMA journal_mode"))
        assertTrue(EpgStore(driver).enableSearch())
        driver.close()
    }

    @Test
    fun guideStoreWorksEndToEnd() {
        val driver = open()
        val store = EpgStore(driver)
        assertTrue(store.enableSearch())
        assertEquals(60, store.writeSnapshot(source, 1, programmes(3, 20, 1)))
        store.indexSearch(source, 1)
        store.activate(source, 1)
        val rows = store.window(source, listOf("ch1", "ch2"), base + 1.hours, base + 3.hours)
        assertEquals(8, rows.size)
        assertEquals(12, store.search(source, "cafe").size, "unicode61 removes diacritics")

        store.writeSnapshot(source, 2, programmes(1, 10, 2))
        store.indexSearch(source, 2)
        store.activate(source, 2)
        assertEquals(0, store.count(source, 1))
        assertEquals(2, store.search(source, "football").size)
        driver.close()
    }

    @Test
    fun transactionsRollBackAndDataSurvivesReopening() {
        val driver = open()
        val store = EpgStore(driver)
        store.writeSnapshot(source, 1, programmes(1, 5, 1))
        val database = IptvDatabase(driver)
        assertFailsWith<IllegalStateException> {
            database.transaction {
                database.epgQueries.insertProgram(source.value, 1, "extra", "ch0", 0, 1, "t", null, null)
                error("abort")
            }
        }
        assertEquals(5, store.count(source, 1), "the failed transaction left no row")
        driver.close()

        val reopened = open()
        assertEquals(5, EpgStore(reopened).count(source, 1))
        assertEquals("${IptvDatabase.Schema.version}", reopened.single("PRAGMA user_version"))
        reopened.close()
    }

    @Test
    fun concurrentWritersDoNotInterleaveTransactions() {
        val driver = open()
        val database = IptvDatabase(driver)
        val workers = (0 until 4).map { worker ->
            thread {
                repeat(50) { batch ->
                    database.transaction {
                        repeat(20) { row ->
                            database.epgQueries.insertProgram(
                                source.value, 7, "w${worker}_${batch}_$row", "ch$worker", row.toLong(),
                                row + 1L, "t", null, null,
                            )
                        }
                    }
                }
            }
        }
        workers.forEach { it.join() }
        assertEquals(4_000, EpgStore(driver).count(source, 7))
        driver.close()
    }
}
