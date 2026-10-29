package app.iptvplayer.domain.live

/**
 * Luz's own channel numbers, which never move when the provider reshuffles (ADR-0052). The first time, the provider's
 * numbers where they are present and unique, the rest from the lowest free number in list order; later, every family
 * keeps its number, a new one takes the highest plus one, and a family that vanished keeps its number for 90 days, so
 * it comes back the same.
 */
public object ChannelNumbers {
    /** A family's number and when it was last seen (epoch ms). */
    public data class Assigned(public val number: Int, public val seenAt: Long)

    /** [families] in the provider's order, each with the provider's own number if any; returns the whole table. */
    public fun assign(existing: Map<String, Assigned>, families: List<Pair<String, Int?>>, now: Long): Map<String, Assigned> {
        val present = families.map { it.first }.toHashSet()
        val table = LinkedHashMap<String, Assigned>()
        for ((key, held) in existing) {
            when {
                key in present -> table[key] = Assigned(held.number, now)
                now - held.seenAt <= HOLD_MS -> table[key] = held
            }
        }
        val used = table.values.map { it.number }.toHashSet()
        val fresh = families.filter { it.first !in table }
        if (existing.isEmpty()) {
            val counts = fresh.mapNotNull { it.second }.groupingBy { it }.eachCount()
            for ((key, number) in fresh) {
                if (number != null && number > 0 && counts[number] == 1 && number !in used) {
                    table[key] = Assigned(number, now)
                    used += number
                }
            }
            var next = 1
            for ((key, _) in fresh) {
                if (key in table) continue
                while (next in used) next++
                table[key] = Assigned(next, now)
                used += next
            }
        } else {
            var next = (used.maxOrNull() ?: 0) + 1
            for ((key, _) in fresh) {
                if (key in table) continue
                table[key] = Assigned(next++, now)
            }
        }
        return table
    }

    private const val HOLD_MS = 90L * 24 * 60 * 60 * 1000
}
