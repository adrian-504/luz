package app.iptvplayer.epg

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.domain.model.ChannelEpgLink
import app.iptvplayer.domain.model.DisplayName
import app.iptvplayer.domain.model.EpgChannel
import app.iptvplayer.domain.model.EpgMatchMethod
import app.iptvplayer.domain.model.Program
import app.iptvplayer.domain.model.ProgramFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EpgTest {
    private val source = EpgSourceId("epg-a")

    private fun epg(id: String, vararg names: String) = EpgChannel(EpgChannelKey(source, id), names.map { DisplayName(it, null) }, null)

    private fun candidate(id: String, name: String, tvgId: String? = null, xtream: Boolean = false) =
        EpgMatchCandidate(ChannelId(id), name, tvgId, xtream)

    private val guide = listOf(
        epg("news.example", "Example News"),
        epg("sports1.example", "Demo Sports 1"),
        epg("kids.example", "Example Kids"),
        epg("dup-a.example", "Duplicate Name"),
        epg("dup-b.example", "Duplicate Name"),
        epg("fr.docs.example", "|FR| Documentaires HD"),
        epg("symbols-a.example", "+++"),
        epg("symbols-b.example", "+++"),
    )

    @Test
    fun matchingOrderAndConfidence() {
        val channels = listOf(
            candidate("c1", "Anything", tvgId = " NEWS.example "),
            candidate("c2", "Demo Sports 1", tvgId = "missing.example"),
            candidate("c3", "UK: Example Kids FHD"),
            candidate("c4", "Duplicate Name"),
            candidate("c5", "Nothing Like It"),
            candidate("c6", "Documentaires"),
            candidate("c7", "Xtream Channel", tvgId = "kids.example", xtream = true),
            candidate("c8", "Example News", tvgId = null),
            candidate("c9", "+++"),
        )
        val result = EpgMatcher.match(
            channels,
            guide,
            sourcePriority = 0,
            overrides = mapOf(ChannelId("c8") to EpgChannelKey(source, "sports1.example")),
        )
        val byChannel = result.links.associateBy { it.channelId.value }
        assertEquals(EpgMatchMethod.TVG_ID to 95, byChannel.getValue("c1").let { it.method to it.confidence })
        assertEquals("news.example", byChannel.getValue("c1").epgChannelKey.channelId)
        assertEquals(EpgMatchMethod.NAME_EXACT, byChannel.getValue("c2").method, "unknown tvg-id falls through to names")
        assertEquals(
            EpgMatchMethod.NAME_NORMALIZED to "kids.example",
            byChannel.getValue("c3").let {
                it.method to
                    it.epgChannelKey.channelId
            },
        )
        assertEquals(
            EpgMatchMethod.NAME_NORMALIZED to "fr.docs.example",
            byChannel.getValue("c6").let {
                it.method to
                    it.epgChannelKey.channelId
            },
        )
        assertEquals(EpgMatchMethod.XTREAM_EPG_ID, byChannel.getValue("c7").method)
        assertEquals(
            EpgMatchMethod.USER_OVERRIDE to "sports1.example",
            byChannel.getValue("c8").let {
                it.method to
                    it.epgChannelKey.channelId
            },
        )
        assertEquals(
            listOf(ChannelId("c4"), ChannelId("c9")),
            result.ambiguous,
            "duplicate exact names are ambiguous even when normalization leaves nothing",
        )
        assertEquals(listOf(ChannelId("c5")), result.unmatched)
    }

    @Test
    fun priorityMerge() {
        val key = { s: String -> EpgChannelKey(EpgSourceId(s), "x") }
        val links = listOf(
            ChannelEpgLink(ChannelId("c"), key("secondary"), EpgMatchMethod.TVG_ID, 95, epgSourcePriority = 1),
            ChannelEpgLink(ChannelId("c"), key("primary-weak"), EpgMatchMethod.NAME_NORMALIZED, 60, epgSourcePriority = 0),
            ChannelEpgLink(ChannelId("c"), key("primary-strong"), EpgMatchMethod.NAME_EXACT, 80, epgSourcePriority = 0),
            ChannelEpgLink(ChannelId("d"), key("secondary"), EpgMatchMethod.TVG_ID, 95, epgSourcePriority = 1),
        )
        val merged = EpgMatcher.mergeByPriority(links).associate { it.channelId.value to it.epgChannelKey.epgSourceId.value }
        assertEquals(mapOf("c" to "primary-strong", "d" to "secondary"), merged)
    }

    private val t0 = Instant.parse("2026-09-14T12:00:00Z")

    private fun program(startMinutes: Int, endMinutes: Int, title: String) = Program(
        ProgramId("p$startMinutes"), EpgChannelKey(source, "c"), t0 + startMinutes.minutes, t0 + endMinutes.minutes, title,
        null, null, emptyList(), null, emptyList(), null, ProgramFlags(), null,
    )

    private val day = listOf(program(0, 30, "A"), program(30, 90, "B"), program(120, 180, "C"))

    @Test
    fun nowNextIncludingGapsAndEdges() {
        val during = GuideMath.nowNext(day, t0 + 45.minutes)
        assertEquals("B", during.current?.title)
        assertEquals("C", during.next?.title)
        assertEquals(0.25, during.progress)
        assertEquals(t0 + 90.minutes, during.validUntil)

        val boundary = GuideMath.nowNext(day, t0 + 30.minutes)
        assertEquals("B", boundary.current?.title, "a programme starting exactly now is current")

        val gap = GuideMath.nowNext(day, t0 + 100.minutes)
        assertNull(gap.current)
        assertEquals("C", gap.next?.title)
        assertNull(gap.progress)
        assertEquals(t0 + 120.minutes, gap.validUntil)

        val before = GuideMath.nowNext(day, t0 - 10.minutes)
        assertEquals(null to "A", before.current?.title to before.next?.title)
        val after = GuideMath.nowNext(day, t0 + 200.minutes)
        assertEquals(null to null, after.current to after.next)
        assertNull(GuideMath.nowNext(emptyList(), t0).validUntil)
    }

    @Test
    fun cellsAreClippedToTheWindow() {
        val window = TimeWindow(t0 + 15.minutes, t0 + 135.minutes)
        val cells = GuideMath.cells(day, window, pixelsPerMinute = 4.0)
        assertEquals(listOf("A", "B", "C"), cells.map { it.program.title })
        assertEquals(0.0 to 60.0, cells[0].x to cells[0].width)
        assertTrue(cells[0].clippedAtStart)
        assertEquals(60.0 to 240.0, cells[1].x to cells[1].width)
        assertEquals(420.0 to 60.0, cells[2].x to cells[2].width)
        assertTrue(cells[2].clippedAtEnd)
        assertEquals(t0 + 15.minutes - 60.minutes * 24, window.earliestRelevantStart)
        assertFailsWith<IllegalArgumentException> { TimeWindow(t0, t0) }
    }
}
