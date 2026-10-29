package app.iptvplayer.domain.sport

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The viewer's followed matches as a calendar file for their phone (s3, ADR-0057): RFC 5545 text, one event per match,
 * in UTC, 2 h 15 min long, no alarms and no place (Luz claims no channel). Each event's UID is the match's id, so adding
 * the file again updates the same events instead of doubling them.
 */
public object MatchCalendar {
    public data class Match(public val id: String, public val title: String, public val competition: String, public val kickoff: Instant)

    public val LENGTH: Duration = 135.minutes

    public fun ics(matches: List<Match>, now: Instant): String = buildString {
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Luz//Matches//EN")
        line("CALSCALE:GREGORIAN")
        line("METHOD:PUBLISH")
        for (match in matches) {
            line("BEGIN:VEVENT")
            line("UID:${match.id}@luz.invalid")
            line("DTSTAMP:${utc(now)}")
            line("DTSTART:${utc(match.kickoff)}")
            line("DTEND:${utc(match.kickoff + LENGTH)}")
            line("SUMMARY:${escape(match.title)}")
            line("DESCRIPTION:${escape(match.competition)}")
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
    }

    /** One content line, folded at 75 octets (RFC 5545 section 3.1) without splitting a character, ended with CRLF. */
    private fun StringBuilder.line(text: String) {
        var octets = 0
        for (char in text) {
            val size = char.toString().encodeToByteArray().size
            // Continuation lines start with a space, which counts towards their 75.
            if (octets + size > MAX_OCTETS) {
                append(CRLF).append(' ')
                octets = 1
            }
            append(char)
            octets += size
        }
        append(CRLF)
    }

    /** RFC 5545 section 3.3.11: backslash, semicolon, comma and line breaks are escaped in text. */
    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")

    /** "20261101T200000Z": the instant in UTC, to the second. */
    private fun utc(at: Instant): String {
        val iso = at.toString() // 2026-11-01T20:00:00Z (or with a fraction)
        val date = iso.substring(0, DATE_END).replace("-", "")
        val time = iso.substring(DATE_END + 1, TIME_END).replace(":", "")
        return "${date}T${time}Z"
    }

    private const val CRLF = "\r\n"
    private const val MAX_OCTETS = 75
    private const val DATE_END = 10
    private const val TIME_END = 19
}
