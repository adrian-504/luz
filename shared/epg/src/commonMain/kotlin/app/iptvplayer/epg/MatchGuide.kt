package app.iptvplayer.epg

import app.iptvplayer.domain.text.SearchFold
import kotlin.time.Instant

/**
 * Finding a match in the viewer's own guide (4.16, ADR-0057): from the names Luz knows for the two teams, the guide
 * search to run, and the order to show what it found in — programmes naming both teams first, then those naming one,
 * nearest to kick-off first within each. It never picks a channel and never drops or relabels a row: what comes back is
 * exactly the guide's rows, reordered.
 */
public object MatchGuide {
    /**
     * The full-text query for programmes naming either team: each of their names as a phrase, joined with OR. Null when
     * neither team has a name long enough to search for ([MIN_NAME] letters), so nothing is searched for "fc".
     */
    public fun query(home: Collection<String>, away: Collection<String>): String? {
        val phrases = (home + away).map(::plain).filter { it.length >= MIN_NAME }.distinct()
        if (phrases.isEmpty()) return null
        // Quotes make each name a phrase; a name's own quotes are dropped so it cannot end one early.
        return phrases.joinToString(" OR ") { "\"" + it.replace("\"", "") + "\"" }
    }

    /** [rows] in the order "In your guide" shows them: both teams first, then one, each nearest to [kickoff] first. */
    public fun <T> order(
        rows: List<T>,
        home: Collection<String>,
        away: Collection<String>,
        kickoff: Instant,
        title: (T) -> String,
        start: (T) -> Instant,
    ): List<T> {
        val homeNames = home.map(::plain).filter { it.length >= MIN_NAME }
        val awayNames = away.map(::plain).filter { it.length >= MIN_NAME }
        fun names(row: T, names: List<String>): Boolean {
            val words = " " + plain(title(row)) + " "
            return names.any { " $it " in words }
        }
        return rows.sortedWith(
            compareBy<T>({ if (names(it, homeNames) && names(it, awayNames)) 0 else 1 }, { (start(it) - kickoff).absoluteValue }),
        )
    }

    private fun plain(text: String): String = SearchFold.spellings(text).joinToString(" ")

    private const val MIN_NAME = 3
}
