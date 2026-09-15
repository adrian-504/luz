package app.iptvplayer.epg

import app.iptvplayer.domain.model.Program
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

public data class TimeWindow(public val start: Instant, public val end: Instant) {
    init {
        require(end > start) { "window end must be after start" }
    }

    public val duration: Duration get() = end - start

    /** The query lower bound for programme starts: programmes are at most 24 h long after normalization (EPG.md §2). */
    public val earliestRelevantStart: Instant get() = start - MAX_PROGRAMME_DURATION

    public companion object {
        public val MAX_PROGRAMME_DURATION: Duration = 24.hours
    }
}

public data class NowNext(
    public val current: Program?,
    public val next: Program?,
    /** 0.0–1.0 through [current], or null when nothing is on. */
    public val progress: Double?,
    /** When this result stops being correct: the end of [current] or the start of [next]. */
    public val validUntil: Instant?,
)

/** A programme placed on the guide grid; positions are precomputed so UI layout does no date math per frame (EPG.md §4). */
public data class GuideCell(
    public val program: Program,
    public val x: Double,
    public val width: Double,
    public val clippedAtStart: Boolean,
    public val clippedAtEnd: Boolean,
)

public object GuideMath {
    /** [programmes] must be one channel's normalized programmes sorted by start (as queried from storage). */
    public fun nowNext(programmes: List<Program>, now: Instant): NowNext {
        var low = 0
        var high = programmes.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (programmes[mid].start <= now) low = mid + 1 else high = mid
        }
        // programmes[low - 1] is the last one that started at or before now.
        val candidate = programmes.getOrNull(low - 1)
        val current = candidate?.takeIf { now < it.end }
        val next = programmes.getOrNull(low)
        val progress = current?.let { ((now - it.start) / (it.end - it.start)).coerceIn(0.0, 1.0) }
        val validUntil = listOfNotNull(current?.end, next?.start).minOrNull()
        return NowNext(current, next, progress, validUntil)
    }

    /** Cells for [programmes] intersecting [window], at [pixelsPerMinute]. */
    public fun cells(programmes: List<Program>, window: TimeWindow, pixelsPerMinute: Double): List<GuideCell> =
        programmes.filter { it.end > window.start && it.start < window.end }.map { program ->
            val start = maxOf(program.start, window.start)
            val end = minOf(program.end, window.end)
            GuideCell(
                program = program,
                x = (start - window.start).inWholeSeconds / 60.0 * pixelsPerMinute,
                width = (end - start).inWholeSeconds / 60.0 * pixelsPerMinute,
                clippedAtStart = program.start < window.start,
                clippedAtEnd = program.end > window.end,
            )
        }
}
