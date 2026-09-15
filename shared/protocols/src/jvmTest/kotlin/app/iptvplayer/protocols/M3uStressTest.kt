package app.iptvplayer.protocols

import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.ParseOutcome
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.m3u.M3uImporter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.measureTimedValue

/**
 * Large-playlist stress on the JVM host. Timings are printed for trend-watching only; they are not device
 * performance results (docs/PERFORMANCE.md: gates are measured on reference hardware).
 */
class M3uStressTest {
    private fun playlist(channels: Int): ByteArray = buildString {
        append("#EXTM3U url-tvg=\"https://epg.example.org/large.xml.gz\"\n")
        for (i in 0 until channels) {
            val group = "Group ${i % 50}"
            append("#EXTINF:-1 tvg-id=\"ch$i.example\" tvg-name=\"Example Channel $i\" ")
            append("tvg-logo=\"https://img.example.com/logo/$i.png\" group-title=\"$group\",Example Channel $i HD\n")
            if (i % 10 == 0) {
                append("http://provider.example.com/live/canary-user/CANARY-PW-7f3a9c-DO-NOT-LOG/$i.ts\n")
            } else {
                append("https://cdn.example.net/live/ch$i/index.m3u8\n")
            }
        }
    }.encodeToByteArray()

    private fun run(channels: Int) {
        val bytes = playlist(channels)
        var emitted = 0
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val (result, duration) = measureTimedValue {
            M3uImporter.import(ByteArraySource(bytes), PlaylistId("stress")) { if (it is ContentItem.ChannelItem) emitted++ }
        }
        val after = runtime.totalMemory() - runtime.freeMemory()
        assertEquals(ParseOutcome.Completed, result.outcome)
        assertEquals(channels, result.counts.channels)
        assertEquals(channels, emitted)
        assertEquals(50, result.counts.groups)
        println(
            "M3U stress (JVM host, informational): $channels channels, ${bytes.size / 1024} KiB in $duration, heap delta ~${(after - before) / (1024 * 1024)} MiB",
        )
    }

    @Test
    fun tenThousandChannels() = run(10_000)

    @Test
    fun hundredThousandChannels() = run(100_000)
}
