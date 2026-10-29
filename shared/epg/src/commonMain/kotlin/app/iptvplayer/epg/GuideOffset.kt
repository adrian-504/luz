package app.iptvplayer.epg

import app.iptvplayer.domain.text.SearchFold
import kotlin.time.Instant

/**
 * A guide that writes its times in local time without saying so is read as UTC and shows programmes an hour or two off
 * (EPG.md §2 rule 1). The provider's own short guide uses exact timestamps, so comparing the two measures the error
 * (ADR-0056): programmes with the same title, matched one to one, must agree on one whole or half hour.
 */
public object GuideOffset {
    /** One programme as either guide writes it. */
    public data class Listing(public val title: String, public val start: Instant)

    /**
     * The minutes to add to [guide]'s times, or null when there is no clear answer: at least [MIN_AGREEING] programmes
     * matched by title must all agree on the same shift, a multiple of 30 minutes; one disagreeing pair means no shift.
     */
    public fun measure(pairs: List<Pair<List<Listing>, List<Listing>>>): Int? {
        val shifts = mutableListOf<Long>()
        for ((guide, short) in pairs) {
            val byTitle = short.groupBy { key(it.title) }
            for (listing in guide) {
                val match = byTitle[key(listing.title)]?.singleOrNull() ?: continue
                shifts += (match.start - listing.start).inWholeMinutes
            }
        }
        if (shifts.size < MIN_AGREEING) return null
        val first = shifts.first()
        if (shifts.any { it != first } || first % STEP_MINUTES != 0L) return null
        return first.toInt()
    }

    private fun key(title: String) = SearchFold.spellings(title).joinToString(" ")

    private const val MIN_AGREEING = 5
    private const val STEP_MINUTES = 30L
}
