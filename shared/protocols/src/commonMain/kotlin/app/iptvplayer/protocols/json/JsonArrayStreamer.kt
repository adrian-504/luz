package app.iptvplayer.protocols.json

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.ports.ByteSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Streams the elements of a top-level JSON array without materializing the whole document (Xtream lists can be tens of
 * megabytes). The splitter only tracks strings, escapes and nesting; each complete element is parsed on its own by
 * kotlinx.serialization, so memory is bounded by one element (ADR-0022). Never throws on malformed input.
 */
internal class JsonArrayStreamer(
    private val maxTotalBytes: Long,
    private val maxElementBytes: Int,
    private val maxDepth: Int,
    private val maxElements: Long,
    private val bufferSize: Int = 64 * 1024,
) {
    sealed interface Outcome {
        data class Completed(val elements: Long) : Outcome

        /** The body is a JSON object, not an array (e.g. `{"user_info":{"auth":0}}` returned for any action). */
        data class NotAnArray(val objectElement: JsonElement?) : Outcome

        /** Truncated or syntactically broken; [elements] elements were delivered before the problem. */
        data class Malformed(val elements: Long, val byteOffset: Long) : Outcome

        data class Stopped(val limit: LimitKind, val elements: Long) : Outcome

        data object Empty : Outcome
    }

    fun stream(source: ByteSource, onElement: (JsonElement) -> Unit): Outcome = Run(source, onElement).execute()

    /** Parses a whole, bounded JSON document (objects such as `player_api.php` auth or `get_series_info`). */
    fun parseDocument(source: ByteSource): JsonElement? {
        val bytes = ByteArray(minOf(maxElementBytes, 4096))
        var buffer = bytes
        var length = 0
        val chunk = ByteArray(bufferSize)
        while (true) {
            val read = source.read(chunk, 0, chunk.size)
            if (read < 0) break
            if (length + read > maxElementBytes) return null
            if (length + read > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + read).coerceAtMost(maxElementBytes))
            chunk.copyInto(buffer, length, 0, read)
            length += read
        }
        return parseBounded(buffer.decodeToString(0, length), maxDepth)
    }

    private inner class Run(private val source: ByteSource, private val onElement: (JsonElement) -> Unit) {
        private val buffer = ByteArray(bufferSize)
        private var position = 0
        private var limit = 0
        private var consumed = 0L
        private var eof = false

        private var element = ByteArray(minOf(maxElementBytes, 4096))
        private var elementLength = 0
        private var delivered = 0L

        fun execute(): Outcome {
            val first = skipWhitespace(skipBom = true)
            return when (first) {
                -1 -> Outcome.Empty
                '{'.code -> capture(first)?.let { Outcome.NotAnArray(null) } ?: Outcome.NotAnArray(parseElement())
                '['.code -> elements()
                else -> Outcome.Malformed(0, consumed)
            }
        }

        private fun elements(): Outcome {
            var b = skipWhitespace()
            if (b == ']'.code) return end()
            while (true) {
                if (b == -1) return Outcome.Malformed(delivered, consumed)
                capture(b)?.let { return it }
                if (delivered >= maxElements) return Outcome.Stopped(LimitKind.RECORD_COUNT, delivered)
                val parsed = parseElement() ?: return Outcome.Malformed(delivered, consumed)
                onElement(parsed)
                delivered++
                b = skipWhitespace()
                when (b) {
                    ','.code -> b = skipWhitespace()
                    ']'.code -> return end()
                    else -> return Outcome.Malformed(delivered, consumed)
                }
            }
        }

        private fun end(): Outcome = if (skipWhitespace() == -1) Outcome.Completed(delivered) else Outcome.Malformed(delivered, consumed)

        /** Copies one element starting with [first] into [element]; returns a terminal outcome on failure. */
        private fun capture(first: Int): Outcome? {
            elementLength = 0
            when (first) {
                '{'.code, '['.code -> {
                    var depth = 0
                    var inString = false
                    var escaped = false
                    var b = first
                    while (true) {
                        if (b == -1) return Outcome.Malformed(delivered, consumed)
                        append(b)?.let { return it }
                        if (inString) {
                            when {
                                escaped -> escaped = false
                                b == '\\'.code -> escaped = true
                                b == '"'.code -> inString = false
                            }
                        } else {
                            when (b) {
                                '"'.code -> inString = true
                                '{'.code, '['.code -> if (++depth > maxDepth) return Outcome.Stopped(LimitKind.DEPTH, delivered)
                                '}'.code, ']'.code -> if (--depth == 0) return null
                            }
                        }
                        b = nextByte()
                    }
                }
                '"'.code -> {
                    append(first)?.let { return it }
                    var escaped = false
                    while (true) {
                        val b = nextByte()
                        if (b == -1) return Outcome.Malformed(delivered, consumed)
                        append(b)?.let { return it }
                        when {
                            escaped -> escaped = false
                            b == '\\'.code -> escaped = true
                            b == '"'.code -> return null
                        }
                    }
                }
                else -> {
                    append(first)?.let { return it }
                    while (true) {
                        val peek = peekByte()
                        if (peek == -1 || peek == ','.code || peek == ']'.code || isWhitespace(peek)) return null
                        append(nextByte())?.let { return it }
                    }
                }
            }
        }

        private fun parseElement(): JsonElement? = parseBounded(element.decodeToString(0, elementLength), maxDepth)

        private fun append(b: Int): Outcome? {
            if (consumed > maxTotalBytes) return Outcome.Stopped(LimitKind.DOWNLOAD_SIZE, delivered)
            if (elementLength >= maxElementBytes) return Outcome.Stopped(LimitKind.TEXT_LENGTH, delivered)
            if (elementLength == element.size) element = element.copyOf(minOf(element.size * 2, maxElementBytes))
            element[elementLength++] = b.toByte()
            return null
        }

        private fun fill(): Boolean {
            if (position < limit) return true
            if (eof) return false
            val read = source.read(buffer, 0, buffer.size)
            if (read <= 0) {
                eof = true
                return false
            }
            position = 0
            limit = read
            return true
        }

        private fun nextByte(): Int {
            if (!fill()) return -1
            consumed++
            return buffer[position++].toInt() and 0xFF
        }

        private fun peekByte(): Int = if (fill()) buffer[position].toInt() and 0xFF else -1

        private fun skipWhitespace(skipBom: Boolean = false): Int {
            var b = nextByte()
            if (skipBom && b == 0xEF && peekByte() == 0xBB) {
                nextByte()
                nextByte()
                b = nextByte()
            }
            while (b != -1 && isWhitespace(b)) b = nextByte()
            return b
        }

        private fun isWhitespace(b: Int): Boolean = b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D
    }

    private companion object {
        /** Rejects nesting deeper than [maxDepth] before handing text to the recursive parser. */
        fun parseBounded(text: String, maxDepth: Int): JsonElement? {
            var depth = 0
            var inString = false
            var escaped = false
            for (c in text) {
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '{', '[' -> if (++depth > maxDepth) return null
                        '}', ']' -> depth--
                    }
                }
            }
            return try {
                Json.parseToJsonElement(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
