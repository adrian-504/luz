package app.iptvplayer.storage

import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.storage.db.IptvDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Real provider guides repeat programmes: the owner's provider sent the same programme id twice on 2026-09-16, which used to
 * abort the import (UNIQUE constraint) and take the app down. Duplicates are now ignored and the import completes.
 */
class EpgDuplicateProgrammeTest {
    private val file: File = File.createTempFile("epg-duplicates", ".db").also { it.delete() }
    private val driver = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    private val store = EpgStore(driver)
    private val source = EpgSourceId("src-1")
    private val start = Instant.parse("2026-09-16T10:00:00Z")

    @AfterTest
    fun cleanUp() {
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun programme(id: String, minutes: Int) = ProgramRow(
        ProgramId(id),
        "channel-1",
        start + minutes.minutes,
        start + (minutes + 30).minutes,
        "Programme $id",
        null,
        null,
    )

    @Test
    fun duplicateProgrammesAreIgnoredAndTheImportFinishes() {
        val writer = store.beginSnapshot(source, snapshot = 1, batchSize = 2)
        writer.add(programme("p1", 0))
        writer.add(programme("p1", 0))
        writer.add(programme("p2", 30))
        writer.add(programme("p1", 0))
        val written = writer.finish()
        store.activate(source, 1)

        assertEquals(4, written, "the writer reports what the provider sent")
        store.replaceLinks("playlist-1", listOf(ChannelEpgLinkRow("ch-1", source.value, "channel-1", "EXACT_ID", 100)))
        val stored = store.programmes("playlist-1", listOf("ch-1"), start, start + 120.minutes)
        assertEquals(listOf("Programme p1", "Programme p2"), stored.getValue("ch-1").map { it.title }, "each programme once")
    }
}
