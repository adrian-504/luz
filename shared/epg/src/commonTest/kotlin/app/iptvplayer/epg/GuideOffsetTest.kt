package app.iptvplayer.epg

import app.iptvplayer.epg.GuideOffset.Listing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class GuideOffsetTest {
    private val base = Instant.parse("2026-10-29T18:00:00Z")

    private fun day(shift: Int, count: Int = 6) = (0 until count).map { i ->
        val guide = Listing("Programme $i", base + (i * 30).minutes)
        val short = Listing("Programme $i", base + (i * 30).minutes + shift.minutes)
        guide to short
    }

    private fun pairs(list: List<Pair<Listing, Listing>>) = listOf(list.map { it.first } to list.map { it.second })

    @Test
    fun aFixedShiftIsFound() {
        assertEquals(120, GuideOffset.measure(pairs(day(shift = 120))))
        assertEquals(-60, GuideOffset.measure(pairs(day(shift = -60))))
        assertEquals(0, GuideOffset.measure(pairs(day(shift = 0))))
    }

    @Test
    fun mixedShiftsGiveNone() {
        val mixed = day(shift = 120).dropLast(1) + (Listing("Late", base + 5.hours) to Listing("Late", base + 6.hours))
        assertNull(GuideOffset.measure(pairs(mixed)))
    }

    @Test
    fun fewerThanFiveMatchesGiveNone() {
        assertNull(GuideOffset.measure(pairs(day(shift = 120, count = 4))))
    }

    @Test
    fun aShiftThatIsNotAWholeOrHalfHourGivesNone() {
        assertNull(GuideOffset.measure(pairs(day(shift = 45))))
    }
}
