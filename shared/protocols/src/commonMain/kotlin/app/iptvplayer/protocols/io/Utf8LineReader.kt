package app.iptvplayer.protocols.io

import app.iptvplayer.domain.ports.ByteSource

/**
 * Streaming line reader for untrusted text: accepts LF, CRLF and CR, strips a leading UTF-8 BOM, bounds line length
 * and total bytes, and reports invalid UTF-8 instead of failing. Memory use is one buffer plus one bounded line.
 */
internal class Utf8LineReader(
    private val source: ByteSource,
    private val maxLineBytes: Int,
    private val maxTotalBytes: Long,
    bufferSize: Int = 64 * 1024,
) {
    sealed interface Next {
        data class Line(val number: Long, val text: String, val invalidUtf8: Boolean) : Next

        /** The line exceeded [maxLineBytes]; only its first bytes are kept so callers can tell what it was. */
        data class TooLong(val number: Long, val prefix: String) : Next

        data object ByteLimitExceeded : Next

        data object End : Next
    }

    private val buffer = ByteArray(bufferSize)
    private var start = 0
    private var end = 0
    private var eof = false
    private var skipLineFeed = false
    private var firstLine = true
    private var lineNumber = 0L
    private var totalBytes = 0L

    private var line = ByteArray(minOf(maxLineBytes, 4096).coerceAtLeast(16))
    private var lineLength = 0
    private var lineTooLong = false
    private val prefix = ByteArray(PREFIX_BYTES)
    private var prefixLength = 0
    private var lineHasContent = false

    fun next(): Next {
        while (true) {
            if (start == end) {
                if (eof) return if (lineHasContent) emit() else Next.End
                val read = source.read(buffer, 0, buffer.size)
                if (read < 0) {
                    eof = true
                    continue
                }
                totalBytes += read
                if (totalBytes > maxTotalBytes) return Next.ByteLimitExceeded
                start = 0
                end = read
                continue
            }
            if (skipLineFeed) {
                skipLineFeed = false
                if (buffer[start] == LF) {
                    start++
                    continue
                }
            }
            var i = start
            while (i < end && buffer[i] != LF && buffer[i] != CR) i++
            append(start, i)
            if (i == end) {
                start = end
                continue
            }
            skipLineFeed = buffer[i] == CR
            start = i + 1
            return emit()
        }
    }

    private fun append(from: Int, to: Int) {
        val count = to - from
        if (count == 0) return
        lineHasContent = true
        val toPrefix = minOf(count, PREFIX_BYTES - prefixLength)
        if (toPrefix > 0) {
            buffer.copyInto(prefix, prefixLength, from, from + toPrefix)
            prefixLength += toPrefix
        }
        if (lineTooLong) return
        if (lineLength + count > maxLineBytes) {
            lineTooLong = true
            return
        }
        if (lineLength + count > line.size) line = line.copyOf(maxOf(line.size * 2, lineLength + count).coerceAtMost(maxLineBytes))
        buffer.copyInto(line, lineLength, from, to)
        lineLength += count
    }

    private fun emit(): Next {
        lineNumber++
        val bomOffset = if (firstLine && lineLength >= 3 && line[0] == BOM0 && line[1] == BOM1 && line[2] == BOM2) 3 else 0
        firstLine = false
        val result = if (lineTooLong) {
            Next.TooLong(lineNumber, prefix.decodeToString(0, prefixLength))
        } else {
            var invalid = false
            val text = try {
                line.decodeToString(bomOffset, lineLength, throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                invalid = true
                line.decodeToString(bomOffset, lineLength)
            }
            Next.Line(lineNumber, text, invalid)
        }
        lineLength = 0
        prefixLength = 0
        lineTooLong = false
        lineHasContent = false
        return result
    }

    private companion object {
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
        const val BOM0: Byte = 0xEF.toByte()
        const val BOM1: Byte = 0xBB.toByte()
        const val BOM2: Byte = 0xBF.toByte()
        const val PREFIX_BYTES = 32
    }
}

/** Replays already-read bytes before continuing with the underlying source (content sniffing without re-fetching). */
internal class PrefixReplaySource(private val prefix: ByteArray, private val rest: ByteSource) : ByteSource {
    private var position = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position < prefix.size) {
            val count = minOf(length, prefix.size - position)
            prefix.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
        return rest.read(buffer, offset, length)
    }

    override fun close() {
        rest.close()
    }
}

/** Reads up to [max] bytes from [source] (fewer only at end of stream). */
internal fun readPrefix(source: ByteSource, max: Int): ByteArray {
    val out = ByteArray(max)
    var filled = 0
    while (filled < max) {
        val read = source.read(out, filled, max - filled)
        if (read < 0) break
        filled += read
    }
    return out.copyOf(filled)
}
