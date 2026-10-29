package app.iptvplayer.storage

import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.ProgramId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** "Fix the guide for this channel" (x9, ADR-0054): the viewer's own link wins over every later guide import. */
class GuideFixTest {
    private val file = TestDatabaseFile("guide-fix")
    private val driver = openIptvDatabase(file.path)
    private val store = EpgStore(driver)
    private val source = EpgSourceId("src-1")
    private val start = Instant.parse("2026-10-29T20:00:00Z")

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private fun importGuide() {
        val writer = store.beginSnapshot(source, snapshot = 1, batchSize = 10)
        writer.add(ProgramRow(ProgramId("a1"), "wrong.channel", start, start + 60.minutes, "Wrong programme", null, null))
        writer.add(ProgramRow(ProgramId("b1"), "right.channel", start, start + 60.minutes, "Right programme", null, null))
        writer.finish()
        store.activate(source, 1)
    }

    private fun matcherLinks() =
        store.replaceLinks("playlist-1", listOf(ChannelEpgLinkRow("ch-1", source.value, "wrong.channel", "FUZZY_NAME", 60)))

    private fun nowOnChannel() = store.programmes("playlist-1", listOf("ch-1"), start, start + 30.minutes)["ch-1"]?.firstOrNull()?.title

    @Test
    fun aUserFixWinsOverEverything() {
        importGuide()
        matcherLinks()
        assertEquals("Wrong programme", nowOnChannel())
        val choice = store.guideChannels("playlist-1", start + 10.minutes).single { it.key == "right.channel" }
        assertEquals("Right programme", choice.now, "each choice shows what it lists now")
        store.fixLink("playlist-1", "ch-1", choice, start)
        assertEquals("Right programme", nowOnChannel())
        // The next guide import writes the matcher's links again; the fix stays.
        matcherLinks()
        assertEquals("Right programme", nowOnChannel())
        assertEquals("right.channel" to true, store.linkFor("playlist-1", "ch-1"))
    }

    @Test
    fun clearingTheFixHandsTheChannelBackToTheImport() {
        importGuide()
        matcherLinks()
        val choice = store.guideChannels("playlist-1", start).single { it.key == "right.channel" }
        store.fixLink("playlist-1", "ch-1", choice, start)
        store.clearFix("playlist-1", "ch-1")
        assertNull(store.linkFor("playlist-1", "ch-1"), "nothing until the provider's guide is read again")
        matcherLinks()
        assertEquals("Wrong programme", nowOnChannel())
    }
}
