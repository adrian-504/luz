package app.iptvplayer.domain.library

/**
 * Where the remote belongs after a list refreshes under it (ADR-0051, Design 1.2): on the same item if it is still
 * there; if it is gone, on the item now in its place (the last one if the list grew shorter than that); nowhere if the
 * list is empty. Data never moves the remote otherwise; this only fills a place that vanished.
 */
public object StableLists {
    public fun <K> keepNear(before: List<K>, after: List<K>, focused: K): K? {
        if (focused in after) return focused
        if (after.isEmpty()) return null
        val place = before.indexOf(focused).coerceAtLeast(0)
        return after[place.coerceAtMost(after.lastIndex)]
    }
}
