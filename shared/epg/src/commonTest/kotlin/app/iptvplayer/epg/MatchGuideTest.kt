package app.iptvplayer.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Finding a match in the viewer's guide (4.16): the search, and the order, never a choice of channel. */
class MatchGuideTest {
    private val home = listOf("Example City FC", "Example City", "\u0645\u062b\u0627\u0644 \u0633\u064a\u062a\u064a")
    private val away = listOf("Sample United", "Sample Utd")
    private val kickoff = Instant.parse("2026-11-01T20:00:00Z")

    private data class Row(val title: String, val start: Instant)

    @Test
    fun everyNameIsAPhraseAndShortNamesAreLeftOut() {
        assertEquals(
            "\"example city fc\" OR \"example city\" OR \"\u0645\u062b\u0627\u0644 \u0633\u064a\u062a\u064a\" OR " +
                "\"sample united\" OR \"sample utd\"",
            MatchGuide.query(home, away),
        )
        assertNull(MatchGuide.query(listOf("FC"), listOf("AS")))
    }

    @Test
    fun bothTeamsComeFirstThenTheNearestToKickOff() {
        val rows = listOf(
            Row("Example City magazine", kickoff - 1.hours),
            Row("Sample United v Test Rovers", kickoff + 48.hours),
            Row("Example City v Sample United (replay)", kickoff + 24.hours),
            Row("Live: Example City v Sample United", kickoff),
            // The Arabic name and the Latin one both count.
            Row("\u0645\u062b\u0627\u0644 \u0633\u064a\u062a\u064a \u00d7 Sample Utd", kickoff + 2.hours),
        )
        val ordered = MatchGuide.order(rows, home, away, kickoff, { it.title }, { it.start })
        assertEquals(
            listOf(
                "Live: Example City v Sample United",
                "\u0645\u062b\u0627\u0644 \u0633\u064a\u062a\u064a \u00d7 Sample Utd",
                "Example City v Sample United (replay)",
                "Example City magazine",
                "Sample United v Test Rovers",
            ),
            ordered.map { it.title },
        )
        assertEquals(rows.toSet(), ordered.toSet(), "nothing dropped or relabelled")
    }
}
