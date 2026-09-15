package app.iptvplayer.protocols.xml

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.ports.ByteSource

/** Recoverable problems found while tokenizing; reported, never thrown. */
internal enum class XmlIssue {
    DOCTYPE_SKIPPED,
    UNDEFINED_ENTITY,
    BARE_AMPERSAND,
    INVALID_CHARACTER_REFERENCE,
    MISMATCHED_END_TAG,
    MALFORMED_TAG,
    INVALID_UTF8,
    UNSUPPORTED_ENCODING,
}

/**
 * Streaming pull tokenizer for the XML subset XMLTV uses (ADR-0018): elements, attributes, text, CDATA; comments and
 * processing instructions skipped; DOCTYPE skipped without interpretation; only the five predefined entities and numeric
 * character references are expanded, so external entities and entity-expansion attacks have no effect. Never throws on
 * malformed input; memory is bounded by one text node and the element stack.
 */
internal class XmlTokenizer(
    private val source: ByteSource,
    private val maxTotalBytes: Long,
    private val maxDepth: Int,
    private val maxAttributes: Int,
    private val maxTextBytes: Int,
    private val onIssue: (XmlIssue) -> Unit,
    private val bufferSize: Int = 64 * 1024,
) {
    sealed interface Token {
        data class Start(val name: String, val attributes: Map<String, String>) : Token

        data class End(val name: String) : Token

        data class Text(val text: String) : Token

        /** [truncated] is true when elements were still open at end of input. */
        data class EndOfDocument(val truncated: Boolean) : Token

        data class Stopped(val limit: LimitKind) : Token
    }

    private val buffer = ByteArray(bufferSize)
    private var position = 0
    private var limit = 0
    private var eof = false
    private var consumed = 0L

    private val stack = ArrayList<String>()
    private val pending = ArrayDeque<Token>()
    private var latin1 = false
    private var sawFirstMarkup = false
    private var finished = false

    private var text = ByteArray(1024)
    private var textLength = 0
    private var textOverflow = false

    fun next(): Token {
        pending.removeFirstOrNull()?.let { return it }
        if (finished) return Token.EndOfDocument(stack.isNotEmpty())
        while (true) {
            if (consumed > maxTotalBytes) return stop(LimitKind.DECOMPRESSED_SIZE)
            val b = peek()
            if (b == -1) {
                finished = true
                val flushed = flushText()
                if (flushed != null) {
                    pending.addLast(Token.EndOfDocument(stack.isNotEmpty()))
                    return flushed
                }
                return Token.EndOfDocument(stack.isNotEmpty())
            }
            if (b == '<'.code) {
                // Markup may add to the pending text (CDATA, a stray '<'), so text is flushed only when a tag token appears.
                val token = markup()
                if (textOverflow) return stop(LimitKind.TEXT_LENGTH)
                if (token != null) {
                    val flushed = flushText() ?: return token
                    pending.addFirst(token)
                    return flushed
                }
            } else {
                read()
                if (b == '&'.code) entity(into = null) else rawText(b)
                if (textOverflow) return stop(LimitKind.TEXT_LENGTH)
            }
        }
    }

    private fun stop(limitKind: LimitKind): Token {
        finished = true
        pending.clear()
        return Token.Stopped(limitKind)
    }

    /** Parses markup starting at '<'; returns a token, or null when the markup produced none (comment, PI, ...). */
    private fun markup(): Token? {
        read() // '<'
        val first = peek()
        return when {
            first == '?'.code -> {
                read()
                processingInstruction()
                null
            }
            first == '!'.code -> {
                read()
                bang()
            }
            first == '/'.code -> {
                read()
                endTag()
            }
            isNameStart(first) -> startTag()
            else -> {
                onIssue(XmlIssue.MALFORMED_TAG)
                appendText('<'.code)
                null
            }
        }
    }

    private fun processingInstruction() {
        val content = ArrayList<Byte>()
        var previous = -1
        while (true) {
            val b = read()
            if (b == -1) return
            if (previous == '?'.code && b == '>'.code) break
            if (content.size < 256) content.add(b.toByte())
            previous = b
        }
        if (!sawFirstMarkup) {
            val declaration = content.toByteArray().decodeToString().lowercase()
            val encoding = Regex("""encoding\s*=\s*["']([^"']+)["']""").find(declaration)?.groupValues?.get(1)
            when (encoding) {
                null, "utf-8", "utf8", "us-ascii", "ascii" -> Unit
                "iso-8859-1", "latin1", "latin-1", "windows-1252", "cp1252", "iso-8859-15" -> latin1 = true
                else -> onIssue(XmlIssue.UNSUPPORTED_ENCODING)
            }
        }
        sawFirstMarkup = true
    }

    private fun bang(): Token? {
        sawFirstMarkup = true
        if (matches("--")) {
            skipUntil("-->")
            return null
        }
        if (matches("[CDATA[")) {
            var previous2 = -1
            var previous1 = -1
            while (true) {
                val b = read()
                if (b == -1) break
                if (previous2 == ']'.code && previous1 == ']'.code && b == '>'.code) {
                    textLength -= 2
                    break
                }
                rawText(b)
                if (textOverflow) return null
                previous2 = previous1
                previous1 = b
            }
            return null
        }
        if (matches("DOCTYPE", ignoreCase = true)) onIssue(XmlIssue.DOCTYPE_SKIPPED)
        // Skip DOCTYPE (including any internal subset) or other declarations without interpreting them.
        var bracketDepth = 0
        var quote = -1
        while (true) {
            val b = read()
            if (b == -1) return null
            when {
                quote != -1 -> if (b == quote) quote = -1
                b == '"'.code || b == '\''.code -> quote = b
                b == '['.code -> bracketDepth++
                b == ']'.code -> bracketDepth--
                b == '>'.code && bracketDepth <= 0 -> return null
            }
        }
    }

    private fun startTag(): Token? {
        sawFirstMarkup = true
        val name = name() ?: return malformed()
        val attributes = LinkedHashMap<String, String>()
        while (true) {
            skipWhitespace()
            when (val b = peek()) {
                -1 -> return null
                '>'.code -> {
                    read()
                    return open(name, attributes, selfClosing = false)
                }
                '/'.code -> {
                    read()
                    if (peek() == '>'.code) read() else onIssue(XmlIssue.MALFORMED_TAG)
                    return open(name, attributes, selfClosing = true)
                }
                else -> {
                    if (!isNameStart(b)) {
                        onIssue(XmlIssue.MALFORMED_TAG)
                        read()
                        continue
                    }
                    val attributeName = name() ?: return malformed()
                    skipWhitespace()
                    if (peek() != '='.code) {
                        onIssue(XmlIssue.MALFORMED_TAG)
                        continue
                    }
                    read()
                    skipWhitespace()
                    val value = attributeValue()
                    if (textOverflow) return stop(LimitKind.TEXT_LENGTH)
                    if (attributes.size >= maxAttributes && attributeName !in attributes) return stop(LimitKind.ATTRIBUTE_COUNT)
                    attributes[attributeName] = value
                }
            }
        }
    }

    private fun open(name: String, attributes: Map<String, String>, selfClosing: Boolean): Token {
        if (selfClosing) {
            pending.addLast(Token.End(name))
            return Token.Start(name, attributes)
        }
        stack.add(name)
        if (stack.size > maxDepth) return stop(LimitKind.DEPTH)
        return Token.Start(name, attributes)
    }

    private fun endTag(): Token? {
        val name = name()
        skipWhitespace()
        if (peek() == '>'.code) read() else onIssue(XmlIssue.MALFORMED_TAG)
        if (name == null) return malformed()
        val index = stack.lastIndexOf(name)
        if (index < 0) {
            onIssue(XmlIssue.MISMATCHED_END_TAG)
            return null
        }
        if (index != stack.lastIndex) onIssue(XmlIssue.MISMATCHED_END_TAG)
        // Close every element opened after [name] as well, so consumers always see balanced tokens.
        while (stack.size > index) pending.addLast(Token.End(stack.removeAt(stack.lastIndex)))
        return pending.removeFirst()
    }

    private fun malformed(): Token? {
        onIssue(XmlIssue.MALFORMED_TAG)
        skipUntil(">")
        return null
    }

    private fun attributeValue(): String {
        val quote = peek()
        val bytes = ValueBuffer()
        if (quote == '"'.code || quote == '\''.code) {
            read()
            while (true) {
                val b = read()
                if (b == -1 || b == quote) break
                if (b == '&'.code) entity(into = bytes) else rawValue(bytes, b)
                if (bytes.length > maxTextBytes) {
                    textOverflow = true
                    break
                }
            }
        } else {
            onIssue(XmlIssue.MALFORMED_TAG)
            while (true) {
                val b = peek()
                if (b == -1 || b == '>'.code || b == '/'.code || isWhitespace(b)) break
                read()
                rawValue(bytes, b)
                if (bytes.length > maxTextBytes) {
                    textOverflow = true
                    break
                }
            }
        }
        return decode(bytes.bytes, bytes.length)
    }

    /** Expands `&name;` / `&#n;` / `&#xh;` after '&' has been consumed, into [into] or the text buffer. */
    private fun entity(into: ValueBuffer?) {
        val emit: (Int) -> Unit = { if (into != null) into.add(it) else appendText(it) }
        val nameBytes = StringBuilder()
        while (nameBytes.length < 12) {
            val b = peek()
            if (b == ';'.code) break
            if (b == -1 || !(isNameChar(b) || b == '#'.code)) {
                onIssue(XmlIssue.BARE_AMPERSAND)
                emit('&'.code)
                nameBytes.forEach { c -> emit(c.code) }
                return
            }
            nameBytes.append(read().toChar())
        }
        if (peek() != ';'.code) {
            onIssue(XmlIssue.BARE_AMPERSAND)
            emit('&'.code)
            nameBytes.forEach { c -> emit(c.code) }
            return
        }
        read() // ';'
        val entityName = nameBytes.toString()
        val codePoint = when {
            entityName == "lt" -> '<'.code
            entityName == "gt" -> '>'.code
            entityName == "amp" -> '&'.code
            entityName == "quot" -> '"'.code
            entityName == "apos" -> '\''.code
            entityName.startsWith("#x") || entityName.startsWith("#X") -> entityName.substring(2).toIntOrNull(16) ?: -2
            entityName.startsWith("#") -> entityName.substring(1).toIntOrNull() ?: -2
            else -> -1
        }
        when {
            codePoint == -1 -> onIssue(XmlIssue.UNDEFINED_ENTITY) // external/custom entities are dropped, never resolved
            !isAllowedCodePoint(codePoint) -> onIssue(XmlIssue.INVALID_CHARACTER_REFERENCE)
            else -> encodeUtf8(codePoint, emit)
        }
    }

    /** A byte taken from the document: Latin-1 documents are transcoded to UTF-8 so decoding is uniform. */
    private fun rawText(b: Int) {
        // Leading whitespace of a text node is never buffered: indentation between elements can be arbitrarily large.
        if (textLength == 0 && isWhitespace(b)) return
        if (latin1 && b >= 0x80) encodeUtf8(b, ::appendText) else appendText(b)
    }

    private fun rawValue(into: ValueBuffer, b: Int) {
        if (latin1 && b >= 0x80) encodeUtf8(b) { into.add(it) } else into.add(b)
    }

    private fun appendText(b: Int) {
        if (textLength >= maxTextBytes) {
            textOverflow = true
            return
        }
        if (textLength == text.size) text = text.copyOf(minOf(text.size * 2, maxTextBytes))
        text[textLength++] = b.toByte()
    }

    private fun flushText(): Token? {
        if (textLength == 0) return null
        if (textLength < 0) {
            textLength = 0
            return null
        }
        var allWhitespace = true
        for (i in 0 until textLength) {
            if (!isWhitespace(text[i].toInt() and 0xFF)) {
                allWhitespace = false
                break
            }
        }
        val token = if (allWhitespace) null else Token.Text(decode(text, textLength))
        textLength = 0
        return token
    }

    private fun decode(bytes: ByteArray, length: Int): String = try {
        bytes.decodeToString(0, length, throwOnInvalidSequence = true)
    } catch (_: CharacterCodingException) {
        onIssue(XmlIssue.INVALID_UTF8)
        bytes.decodeToString(0, length)
    }

    private fun name(): String? {
        if (!isNameStart(peek())) return null
        val sb = StringBuilder()
        while (isNameChar(peek())) {
            sb.append(read().toChar())
            if (sb.length > MAX_NAME_LENGTH) return null
        }
        return sb.toString()
    }

    private fun matches(expected: String, ignoreCase: Boolean = false): Boolean {
        // Only called right after "<!"; mismatches leave the consumed prefix to the generic declaration skipper.
        for (c in expected) {
            val b = peek()
            val same = if (ignoreCase) b != -1 && b.toChar().uppercaseChar() == c.uppercaseChar() else b == c.code
            if (!same) return false
            read()
        }
        return true
    }

    private fun skipUntil(terminator: String) {
        var matched = 0
        while (true) {
            val b = read()
            if (b == -1) return
            matched = if (b == terminator[matched].code) {
                matched + 1
            } else if (b == terminator[0].code) {
                1
            } else {
                0
            }
            if (matched == terminator.length) return
        }
    }

    private fun skipWhitespace() {
        while (isWhitespace(peek())) read()
    }

    private fun fill(): Boolean {
        if (position < limit) return true
        if (eof) return false
        val count = source.read(buffer, 0, buffer.size)
        if (count <= 0) {
            eof = true
            return false
        }
        position = 0
        limit = count
        return true
    }

    private fun peek(): Int = if (fill()) buffer[position].toInt() and 0xFF else -1

    private fun read(): Int {
        if (!fill()) return -1
        consumed++
        return buffer[position++].toInt() and 0xFF
    }

    private class ValueBuffer {
        var bytes = ByteArray(64)
        var length = 0

        fun add(b: Int) {
            if (length == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            bytes[length++] = b.toByte()
        }
    }

    private companion object {
        const val MAX_NAME_LENGTH = 256

        fun isWhitespace(b: Int) = b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D

        fun isNameStart(b: Int) = (b in 'a'.code..'z'.code) || (b in 'A'.code..'Z'.code) || b == '_'.code || b == ':'.code || b >= 0x80

        fun isNameChar(b: Int) = isNameStart(b) || (b in '0'.code..'9'.code) || b == '-'.code || b == '.'.code

        fun isAllowedCodePoint(cp: Int) =
            cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

        fun encodeUtf8(cp: Int, emit: (Int) -> Unit) {
            when {
                cp < 0x80 -> emit(cp)
                cp < 0x800 -> {
                    emit(0xC0 or (cp shr 6))
                    emit(0x80 or (cp and 0x3F))
                }
                cp < 0x10000 -> {
                    emit(0xE0 or (cp shr 12))
                    emit(0x80 or ((cp shr 6) and 0x3F))
                    emit(0x80 or (cp and 0x3F))
                }
                else -> {
                    emit(0xF0 or (cp shr 18))
                    emit(0x80 or ((cp shr 12) and 0x3F))
                    emit(0x80 or ((cp shr 6) and 0x3F))
                    emit(0x80 or (cp and 0x3F))
                }
            }
        }
    }
}
