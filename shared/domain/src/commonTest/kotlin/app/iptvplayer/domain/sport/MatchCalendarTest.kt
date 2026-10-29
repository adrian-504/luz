package app.iptvplayer.domain.sport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The followed matches as a phone calendar file (s3): RFC 5545 lines, folding, escaping, stable ids. */
class MatchCalendarTest {
    private val now = Instant.parse("2026-10-29T18:00:00Z")
    private val match = MatchCalendar.Match(
        "70001",
        "Example City FC \u2013 Sample United",
        "Example League",
        Instant.parse("2026-11-01T20:00:00Z"),
    )

    @Test
    fun oneEventPerMatchInUtcTwoHoursFifteenLong() {
        val ics = MatchCalendar.ics(listOf(match), now)
        val lines = ics.split("\r\n")
        assertEquals("BEGIN:VCALENDAR", lines.first())
        assertTrue("UID:70001@luz.invalid" in lines, "a stable id: adding again updates")
        assertTrue("DTSTART:20261101T200000Z" in lines)
        assertTrue("DTEND:20261101T221500Z" in lines)
        assertTrue("DTSTAMP:20261029T180000Z" in lines)
        assertTrue(lines.none { it.startsWith("LOCATION") || it.startsWith("BEGIN:VALARM") }, "no place, no alarm")
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertTrue(ics.replace("\r\n", "").none { it == '\r' || it == '\n' }, "every line ends with CRLF")
    }

    @Test
    fun longLinesFoldAt75OctetsWithoutSplittingACharacter() {
        val long = match.copy(title = "\u0645\u062b\u0627\u0644 \u0633\u064a\u062a\u064a".repeat(8) + " \u2013 Sample United")
        val ics = MatchCalendar.ics(listOf(long), now)
        for (line in ics.split("\r\n")) assertTrue(line.encodeToByteArray().size <= 75, "too long: $line")
        // Unfolded (CRLF and the one space removed), the title is whole again.
        val unfolded = ics.replace("\r\n ", "")
        assertTrue("SUMMARY:${long.title}" in unfolded.split("\r\n"))
    }

    @Test
    fun textIsEscaped() {
        val odd = match.copy(title = "A, B; C\\D", competition = "Line\nbreak")
        val lines = MatchCalendar.ics(listOf(odd), now).split("\r\n")
        assertTrue("SUMMARY:A\\, B\\; C\\\\D" in lines)
        assertTrue("DESCRIPTION:Line\\nbreak" in lines)
    }
}
