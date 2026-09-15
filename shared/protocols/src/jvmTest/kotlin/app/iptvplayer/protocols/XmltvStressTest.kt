package app.iptvplayer.protocols

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.domain.ports.ParseLimits
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.xmltv.XmltvImportOptions
import app.iptvplayer.protocols.xmltv.XmltvImporter
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue

/**
 * Large-guide stress on the JVM host. Documents are generated on the fly, so memory measurements show the importer's
 * own footprint. Timings are informational only; device gates are measured in Phase 9 (docs/PERFORMANCE.md).
 */
class XmltvStressTest {
    /** Streams an XMLTV document with [channels] × [perChannel] programmes without materializing it. */
    private class GeneratedGuide(private val channels: Int, private val perChannel: Int) : ByteSource {
        private var chunk = ByteArray(0)
        private var offset = 0
        private var channel = -1
        private var done = false
        private val base = 1_789_344_000L // 2026-09-14T00:00:00Z

        private fun nextChunk(): Boolean {
            if (done) return false
            val sb = StringBuilder()
            when {
                channel == -1 -> {
                    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE tv SYSTEM \"xmltv.dtd\">\n<tv>\n")
                    for (c in 0 until channels) {
                        sb.append(
                            "<channel id=\"ch$c.example\"><display-name>Example Channel $c</display-name></channel>\n",
                        )
                    }
                }
                channel < channels -> {
                    var start = base
                    for (p in 0 until perChannel) {
                        val stop = start + 1800 + (p % 4) * 900
                        sb.append("<programme start=\"").append(time(start)).append("\" stop=\"").append(time(stop))
                            .append("\" channel=\"ch").append(channel).append(".example\"><title>Programme ").append(p)
                            .append(
                                "</title><desc>Synthetic description &amp; details for stress testing.</desc><category>News</category></programme>\n",
                            )
                        start = stop
                    }
                }
                else -> {
                    sb.append("</tv>\n")
                    done = true
                }
            }
            channel++
            chunk = sb.toString().encodeToByteArray()
            offset = 0
            return true
        }

        private fun time(epochSeconds: Long): String {
            val t = java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneOffset.UTC)
            return "%04d%02d%02d%02d%02d%02d +0000".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (this.offset >= chunk.size) if (!nextChunk()) return -1
            val n = minOf(length, chunk.size - this.offset)
            chunk.copyInto(buffer, offset, this.offset, this.offset + n)
            this.offset += n
            return n
        }

        override fun close() {}
    }

    private fun run(channels: Int, perChannel: Int) {
        val runtime = Runtime.getRuntime()
        System.gc()
        val before = runtime.totalMemory() - runtime.freeMemory()
        var peak = before
        var emitted = 0
        val (result, duration) = measureTimedValue {
            XmltvImporter.import(GeneratedGuide(channels, perChannel), XmltvImportOptions(EpgSourceId("stress"))) { item ->
                if (item is ContentItem.ProgramItem) {
                    emitted++
                    if (emitted % 50_000 == 0) peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
                }
            }
        }
        assertEquals(ImportStatus.PUBLISHED, result.status, result.diagnostics.take(3).toString())
        assertEquals(channels * perChannel, result.counts.programmes)
        assertEquals(channels * perChannel, emitted)
        val rate = (emitted / duration.inWholeMilliseconds.coerceAtLeast(1).toDouble() * 1000).toInt()
        println(
            "XMLTV stress (JVM host, informational): $emitted programmes in $duration (~$rate/s), sampled heap growth ~${(peak - before) / (1024 * 1024)} MiB",
        )
    }

    @Test
    fun hundredThousandProgrammes() = run(channels = 1_000, perChannel = 100)

    @Test
    fun oneMillionProgrammesStreamWithBoundedMemory() = run(channels = 2_000, perChannel = 500)

    private fun gzip(size: Int, byte: Byte = ' '.code.toByte()): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz ->
            gz.write("<?xml version=\"1.0\"?><tv>".encodeToByteArray())
            val block = ByteArray(1 shl 20) { byte }
            var written = 0
            while (written < size) {
                val n = minOf(block.size, size - written)
                gz.write(block, 0, n)
                written += n
            }
            gz.write("</tv>".encodeToByteArray())
        }
        return out.toByteArray()
    }

    @Test
    fun gzipBombIsStoppedByCompressionRatio() {
        val bomb = gzip(80 * 1024 * 1024)
        assertTrue(bomb.size < 200_000, "80 MiB of whitespace compresses to ${bomb.size} bytes")
        val result = XmltvImporter.import(ByteArraySource(bomb), XmltvImportOptions(EpgSourceId("bomb"))) {}
        assertEquals(ImportStatus.FAILED, result.status)
        assertEquals(DomainError.Limit(LimitKind.COMPRESSION_RATIO), result.error)
    }

    @Test
    fun decompressedSizeLimitApplies() {
        val limits = ParseLimits.XMLTV.copy(maxDecompressedBytes = 1024 * 1024, maxCompressionRatio = null)
        val result = XmltvImporter.import(ByteArraySource(gzip(4 * 1024 * 1024)), XmltvImportOptions(EpgSourceId("big")), limits) {}
        assertEquals(DomainError.Limit(LimitKind.DECOMPRESSED_SIZE), result.error)
    }

    @Test
    fun corruptGzipIsReportedNotThrown() {
        val valid = gzip(10_000)
        val corrupt = valid.copyOf(valid.size / 2)
        val result = XmltvImporter.import(ByteArraySource(corrupt), XmltvImportOptions(EpgSourceId("corrupt"))) {}
        assertEquals(ImportStatus.FAILED, result.status)
        assertEquals(DomainError.Parse("XMLTV_CORRUPT_COMPRESSED_DATA"), result.error)
    }
}
