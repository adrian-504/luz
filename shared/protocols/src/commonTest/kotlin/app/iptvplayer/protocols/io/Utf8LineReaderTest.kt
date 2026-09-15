package app.iptvplayer.protocols.io

import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.protocols.io.Utf8LineReader.Next
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class Utf8LineReaderTest {
    /** Delivers at most [chunk] bytes per read to exercise buffer boundaries. */
    private class Chunked(private val bytes: ByteArray, private val chunk: Int) : ByteSource {
        private var pos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(chunk, length, bytes.size - pos)
            bytes.copyInto(buffer, offset, pos, pos + n)
            pos += n
            return n
        }

        override fun close() {}
    }

    private fun lines(
        bytes: ByteArray,
        chunk: Int = 1 shl 16,
        bufferSize: Int = 64,
        maxLine: Int = 1024,
        maxTotal: Long = Long.MAX_VALUE,
    ): List<Next> {
        val reader = Utf8LineReader(Chunked(bytes, chunk), maxLine, maxTotal, bufferSize)
        val out = ArrayList<Next>()
        while (true) {
            val next = reader.next()
            out.add(next)
            if (next == Next.End || next == Next.ByteLimitExceeded) return out
        }
    }

    private fun texts(result: List<Next>): List<String> = result.filterIsInstance<Next.Line>().map { it.text }

    @Test
    fun handlesLfCrlfCrAndMissingFinalNewline() {
        val input = "a\nb\r\nc\rd\r\n\r\ne".encodeToByteArray()
        for (chunk in 1..8) {
            for (buffer in listOf(1, 2, 3, 64)) {
                assertEquals(listOf("a", "b", "c", "d", "", "e"), texts(lines(input, chunk, buffer)), "chunk=$chunk buffer=$buffer")
            }
        }
    }

    @Test
    fun trailingNewlineDoesNotCreateEmptyLine() {
        assertEquals(listOf("a", "b"), texts(lines("a\nb\n".encodeToByteArray())))
        assertEquals(listOf("a"), texts(lines("a\r\n".encodeToByteArray(), chunk = 2, bufferSize = 2)))
        assertEquals(listOf<Next>(Next.End), lines(ByteArray(0)))
    }

    @Test
    fun stripsUtf8BomOnlyOnFirstLine() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val input = bom + "#EXTM3U\n".encodeToByteArray() + bom + "x".encodeToByteArray()
        val result = texts(lines(input, chunk = 1, bufferSize = 2))
        assertEquals("#EXTM3U", result[0])
        assertEquals(Char(0xFEFF) + "x", result[1])
    }

    @Test
    fun lineNumbersCountEveryLine() {
        val numbers = lines("x\n\ny\n".encodeToByteArray()).filterIsInstance<Next.Line>().map { it.number }
        assertEquals(listOf(1L, 2L, 3L), numbers)
    }

    @Test
    fun invalidUtf8IsReplacedAndFlagged() {
        val input = "ok\n".encodeToByteArray() + byteArrayOf(0x41, 0xFF.toByte(), 0xFE.toByte(), 0x42) + "\n".encodeToByteArray()
        val result = lines(input, chunk = 1, bufferSize = 3).filterIsInstance<Next.Line>()
        assertEquals(false, result[0].invalidUtf8)
        assertTrue(result[1].invalidUtf8)
        assertTrue(result[1].text.startsWith("A") && result[1].text.endsWith("B") && result[1].text.contains(Char(0xFFFD)))
    }

    @Test
    fun multibyteCharactersSplitAcrossReadsDecodeCorrectly() {
        val text = "café 東京 🎬"
        val result = lines((text + "\n").encodeToByteArray(), chunk = 1, bufferSize = 1).filterIsInstance<Next.Line>().single()
        assertEquals(text, result.text)
        assertEquals(false, result.invalidUtf8)
    }

    @Test
    fun overLongLinesAreReportedWithPrefixAndReadingContinues() {
        val input = ("#EXTINF:-1," + "A".repeat(5000) + "\nnext\n").encodeToByteArray()
        val result = lines(input, chunk = 7, bufferSize = 16, maxLine = 100)
        val tooLong = assertIs<Next.TooLong>(result[0])
        assertEquals(1L, tooLong.number)
        assertTrue(tooLong.prefix.startsWith("#EXTINF"))
        assertEquals(listOf("next"), texts(result))
    }

    @Test
    fun exactlyMaxLineLengthIsAccepted() {
        assertEquals(listOf("x".repeat(10)), texts(lines("xxxxxxxxxx\n".encodeToByteArray(), maxLine = 10, bufferSize = 3)))
        assertIs<Next.TooLong>(lines("xxxxxxxxxxx\n".encodeToByteArray(), maxLine = 10)[0])
    }

    @Test
    fun totalByteLimitStopsReading() {
        val result = lines("line1\nline2\nline3\n".encodeToByteArray(), chunk = 4, bufferSize = 4, maxTotal = 8)
        assertEquals(Next.ByteLimitExceeded, result.last())
    }

    @Test
    fun prefixReplaySourceReplaysThenContinues() {
        val source = ByteArraySource("hello world".encodeToByteArray())
        val prefix = readPrefix(source, 5)
        assertEquals("hello", prefix.decodeToString())
        val replay = PrefixReplaySource(prefix, source)
        val buffer = ByteArray(32)
        val sb = StringBuilder()
        while (true) {
            val n = replay.read(buffer, 0, 3)
            if (n < 0) break
            sb.append(buffer.decodeToString(0, n))
        }
        assertEquals("hello world", sb.toString())
        assertEquals(3, readPrefix(ByteArraySource("abc".encodeToByteArray()), 10).size)
    }
}
