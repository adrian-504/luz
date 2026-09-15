package app.iptvplayer.protocols.m3u

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.domain.ports.DiagnosticLocation
import app.iptvplayer.domain.ports.DiagnosticSeverity
import app.iptvplayer.domain.ports.DiagnosticSink
import app.iptvplayer.domain.ports.ImportDiagnostic
import app.iptvplayer.domain.ports.ParseLimits
import app.iptvplayer.domain.ports.ParseOutcome
import app.iptvplayer.domain.ports.RecordSink
import app.iptvplayer.domain.ports.StreamingParser
import app.iptvplayer.domain.security.PercentEncoding
import app.iptvplayer.domain.security.Redactor
import app.iptvplayer.protocols.io.Utf8LineReader

/** Stable diagnostic codes for M3U import (docs/IPTV_PROTOCOLS.md §3.5). */
public object M3uDiagnosticCodes {
    public const val MISSING_HEADER: String = "M3U_MISSING_HEADER"
    public const val EXTINF_WITHOUT_URL: String = "M3U_EXTINF_WITHOUT_URL"
    public const val URL_WITHOUT_EXTINF: String = "M3U_URL_WITHOUT_EXTINF"
    public const val MISSING_TITLE: String = "M3U_MISSING_TITLE"
    public const val INVALID_DURATION: String = "M3U_INVALID_DURATION"
    public const val INVALID_UTF8: String = "M3U_INVALID_UTF8"
    public const val LINE_TOO_LONG: String = "M3U_LINE_TOO_LONG"
    public const val URL_OF_DROPPED_ENTRY: String = "M3U_URL_OF_DROPPED_ENTRY"
    public const val UNTERMINATED_QUOTE: String = "M3U_UNTERMINATED_QUOTE"
    public const val MALFORMED_ATTRIBUTE: String = "M3U_MALFORMED_ATTRIBUTE"
    public const val DUPLICATE_ATTRIBUTE: String = "M3U_DUPLICATE_ATTRIBUTE"
    public const val TOO_MANY_ATTRIBUTES: String = "M3U_TOO_MANY_ATTRIBUTES"
    public const val INVALID_EXTHTTP: String = "M3U_INVALID_EXTHTTP"
    public const val HLS_PLAYLIST: String = "M3U_HLS_PLAYLIST"
    public const val BYTE_LIMIT: String = "M3U_BYTE_LIMIT"
    public const val RECORD_LIMIT: String = "M3U_RECORD_LIMIT"
    public const val UNSUPPORTED_SCHEME: String = "M3U_UNSUPPORTED_SCHEME"
    public const val INVALID_URL: String = "M3U_INVALID_URL"
    public const val INVALID_ARTWORK_URL: String = "M3U_INVALID_ARTWORK_URL"
    public const val INVALID_EPG_URL: String = "M3U_INVALID_EPG_URL"
    public const val NO_USABLE_NAME: String = "M3U_NO_USABLE_NAME"
    public const val NAME_TRUNCATED: String = "M3U_NAME_TRUNCATED"
    public const val DUPLICATE_COLLAPSED: String = "M3U_DUPLICATE_COLLAPSED"
    public const val SERIES_WITHOUT_EPISODE: String = "M3U_SERIES_WITHOUT_EPISODE_NUMBER"
    public const val EXTRAS_TRUNCATED: String = "M3U_EXTRAS_TRUNCATED"
}

/** Raw protocol records. Internal: they never leave shared:protocols (docs/ARCHITECTURE.md §4 rule 4). */
internal sealed interface M3uRecord {
    data class Header(val line: Long, val attributes: Map<String, String>) : M3uRecord

    class Entry(
        val line: Long,
        val durationRaw: String?,
        val attributes: Map<String, String>,
        val title: String?,
        val extGroup: String?,
        val vlcOptions: Map<String, String>,
        val kodiProps: Map<String, String>,
        val httpHeaders: Map<String, String>,
        val url: String,
        val pipeHeaders: Map<String, String>,
    ) : M3uRecord {
        /** URLs may carry credentials, so printing is redacted. */
        override fun toString(): String = "M3uRecord.Entry(line=$line, title=$title, url=${Redactor.STRUCTURAL.redactUrl(url)})"
    }
}

internal class AttributeParse(
    val attributes: LinkedHashMap<String, String>,
    val title: String?,
    val duplicateKeys: Int,
    val malformedTokens: Int,
    val unterminatedQuote: Boolean,
    val droppedAttributes: Int,
)

/** Tokenizes `key="v" key='v' key=v ... ,title` without assuming attribute order (docs/IPTV_PROTOCOLS.md §3.1). */
internal object AttributeTokenizer {
    fun parse(text: String, start: Int, expectTitle: Boolean, maxAttributes: Int): AttributeParse {
        val attributes = LinkedHashMap<String, String>()
        var title: String? = null
        var duplicates = 0
        var malformed = 0
        var unterminated = false
        var dropped = 0
        var i = start
        while (i < text.length) {
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length) break
            if (text[i] == ',') {
                if (expectTitle) {
                    title = text.substring(i + 1)
                    break
                }
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j] != '=' && text[j] != ',' && !text[j].isWhitespace()) j++
            val key = text.substring(i, j).lowercase()
            if (j < text.length && text[j] == '=') {
                val valueStart = j + 1
                val value: String
                if (valueStart < text.length && (text[valueStart] == '"' || text[valueStart] == '\'')) {
                    val close = text.indexOf(text[valueStart], valueStart + 1)
                    if (close >= 0) {
                        value = text.substring(valueStart + 1, close)
                        i = close + 1
                    } else {
                        // Recover at end of line: the last comma still separates the title where possible.
                        unterminated = true
                        val lastComma = if (expectTitle) text.lastIndexOf(',') else -1
                        if (lastComma > valueStart) {
                            value = text.substring(valueStart + 1, lastComma)
                            title = text.substring(lastComma + 1)
                        } else {
                            value = text.substring(valueStart + 1)
                        }
                        i = text.length
                    }
                } else {
                    var e = valueStart
                    while (e < text.length && text[e] != ',' && !text[e].isWhitespace()) e++
                    value = text.substring(valueStart, e)
                    i = e
                }
                when {
                    key.isEmpty() || key.any { it == '"' || it == '\'' } -> malformed++
                    key in attributes -> {
                        duplicates++
                        attributes[key] = value
                    }
                    attributes.size >= maxAttributes -> dropped++
                    else -> attributes[key] = value
                }
                if (unterminated) break
            } else {
                if (key.isNotEmpty()) malformed++
                i = j
            }
        }
        return AttributeParse(attributes, title, duplicates, malformed, unterminated, dropped)
    }
}

/** Flat JSON object of scalar values, as used by `#EXTHTTP:{...}`. Returns null when not a flat object. */
internal object FlatJsonObject {
    fun parse(text: String): Map<String, String>? {
        var i = skipWs(text, 0)
        if (i >= text.length || text[i] != '{') return null
        i = skipWs(text, i + 1)
        val out = LinkedHashMap<String, String>()
        if (i < text.length && text[i] == '}') return out
        while (i < text.length) {
            val key = readString(text, i) ?: return null
            i = skipWs(text, key.second)
            if (i >= text.length || text[i] != ':') return null
            i = skipWs(text, i + 1)
            if (i >= text.length) return null
            if (text[i] == '"') {
                val value = readString(text, i) ?: return null
                out[key.first] = value.first
                i = value.second
            } else {
                var e = i
                while (e < text.length && text[e] != ',' && text[e] != '}' && !text[e].isWhitespace()) e++
                if (e == i || text[i] == '{' || text[i] == '[') return null
                out[key.first] = text.substring(i, e)
                i = e
            }
            i = skipWs(text, i)
            if (i >= text.length) return null
            when (text[i]) {
                ',' -> i = skipWs(text, i + 1)
                '}' -> return if (skipWs(text, i + 1) == text.length) out else null
                else -> return null
            }
        }
        return null
    }

    private fun skipWs(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Returns (value, index after closing quote). */
    private fun readString(text: String, from: Int): Pair<String, Int>? {
        if (from >= text.length || text[from] != '"') return null
        val out = StringBuilder()
        var i = from + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return out.toString() to i + 1
                '\\' -> {
                    if (i + 1 >= text.length) return null
                    when (val esc = text[i + 1]) {
                        '"', '\\', '/' -> out.append(esc)
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 5 >= text.length) return null
                            val code = text.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> return null
                    }
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return null
    }
}

/**
 * Streaming, bounded M3U/M3U8 channel-list parser. Never throws on malformed input; every recovery emits a
 * diagnostic (docs/IPTV_PROTOCOLS.md §3.1).
 */
internal class M3uParser : StreamingParser<M3uRecord> {
    override fun parse(input: ByteSource, limits: ParseLimits, sink: RecordSink<M3uRecord>, diagnostics: DiagnosticSink): ParseOutcome =
        Run(input, limits, sink, diagnostics).execute()

    private class Pending(val line: Long, val durationRaw: String?, val attributes: Map<String, String>, val title: String?) {
        var extGroup: String? = null
        val vlcOptions = LinkedHashMap<String, String>()
        val kodiProps = LinkedHashMap<String, String>()
        val httpHeaders = LinkedHashMap<String, String>()
    }

    private class Run(
        input: ByteSource,
        private val limits: ParseLimits,
        private val sink: RecordSink<M3uRecord>,
        private val diagnostics: DiagnosticSink,
    ) {
        private val reader = Utf8LineReader(input, limits.maxLineOrTokenBytes, limits.maxBytes)
        private var pending: Pending? = null
        private var dropNextUrl = false
        private var sawContent = false
        private var entries = 0L

        fun execute(): ParseOutcome {
            while (true) {
                when (val next = reader.next()) {
                    Utf8LineReader.Next.End -> {
                        flushPendingWithoutUrl()
                        return ParseOutcome.Completed
                    }
                    Utf8LineReader.Next.ByteLimitExceeded -> {
                        error(M3uDiagnosticCodes.BYTE_LIMIT, null, "Playlist exceeds the maximum size")
                        return ParseOutcome.Stopped(LimitKind.DOWNLOAD_SIZE)
                    }
                    is Utf8LineReader.Next.TooLong -> {
                        error(
                            M3uDiagnosticCodes.LINE_TOO_LONG,
                            next.number,
                            "Line exceeds ${limits.maxLineOrTokenBytes} bytes and was skipped",
                            next.prefix,
                        )
                        if (next.prefix.startsWith("#EXTINF", ignoreCase = true)) {
                            flushPendingWithoutUrl()
                            dropNextUrl = true
                        }
                    }
                    is Utf8LineReader.Next.Line -> {
                        val outcome = onLine(next)
                        if (outcome != null) return outcome
                    }
                }
            }
        }

        private fun onLine(next: Utf8LineReader.Next.Line): ParseOutcome? {
            val text = next.text.trim()
            fun tag(name: String) = text.startsWith(name, ignoreCase = true)

            if (text.isEmpty()) return null
            if (next.invalidUtf8) {
                warning(M3uDiagnosticCodes.INVALID_UTF8, next.number, "Invalid UTF-8 replaced with U+FFFD", text)
            }
            val firstContent = !sawContent
            sawContent = true
            when {
                tag("#EXTM3U") -> {
                    val parsed = AttributeTokenizer.parse(text, 7, expectTitle = false, maxAttributes = limits.maxAttributes)
                    reportAttributeIssues(parsed, next.number, text)
                    sink.accept(M3uRecord.Header(next.number, parsed.attributes))
                }
                tag("#EXTINF:") -> {
                    if (firstContent) warning(M3uDiagnosticCodes.MISSING_HEADER, next.number, "Playlist has no #EXTM3U header")
                    flushPendingWithoutUrl()
                    dropNextUrl = false
                    pending = parseExtinf(text, next.number)
                }
                tag("#EXT-X-") && isHlsTag(text) -> {
                    error(M3uDiagnosticCodes.HLS_PLAYLIST, next.number, "This is an HLS stream playlist, not a channel list")
                    return ParseOutcome.Rejected(ValidationFailure.SOURCE_IS_HLS_PLAYLIST)
                }
                tag("#EXTGRP:") -> pending?.extGroup = text.substring(8).trim()
                tag("#EXTVLCOPT:") -> keyValue(text.substring(11))?.let { (k, v) -> pending?.vlcOptions?.put(k, v) }
                tag("#KODIPROP:") -> keyValue(text.substring(10))?.let { (k, v) -> pending?.kodiProps?.put(k, v) }
                tag("#EXTHTTP:") -> pending?.let { p ->
                    val headers = FlatJsonObject.parse(text.substring(9))
                    if (headers == null) {
                        warning(M3uDiagnosticCodes.INVALID_EXTHTTP, next.number, "#EXTHTTP is not a flat JSON object")
                    } else {
                        p.httpHeaders.putAll(headers)
                    }
                }
                text.startsWith("#") -> Unit // comment or unsupported tag
                else -> return onUrl(text, next.number)
            }
            return null
        }

        private fun onUrl(text: String, line: Long): ParseOutcome? {
            if (dropNextUrl) {
                dropNextUrl = false
                info(M3uDiagnosticCodes.URL_OF_DROPPED_ENTRY, line, "URL belongs to a skipped #EXTINF line")
                return null
            }
            val p = pending ?: run {
                info(M3uDiagnosticCodes.URL_WITHOUT_EXTINF, line, "URL without #EXTINF; name derived from URL")
                Pending(line, null, emptyMap(), null)
            }
            pending = null
            val pipe = text.indexOf('|')
            val hasPipeHeaders = pipe > 0 && text.indexOf('=', pipe) > pipe
            val url = if (hasPipeHeaders) text.substring(0, pipe).trim() else text
            val pipeHeaders = if (hasPipeHeaders) parsePipeHeaders(text.substring(pipe + 1)) else emptyMap()
            entries++
            if (entries > limits.maxRecords) {
                error(M3uDiagnosticCodes.RECORD_LIMIT, line, "Playlist exceeds ${limits.maxRecords} entries")
                return ParseOutcome.Stopped(LimitKind.RECORD_COUNT)
            }
            val entry = M3uRecord.Entry(
                p.line, p.durationRaw, p.attributes, p.title, p.extGroup, p.vlcOptions, p.kodiProps, p.httpHeaders, url, pipeHeaders,
            )
            sink.accept(entry)
            return null
        }

        private fun parseExtinf(text: String, line: Long): Pending {
            var i = 8
            while (i < text.length && text[i].isWhitespace()) i++
            val durationStart = i
            while (i < text.length && text[i] != ',' && !text[i].isWhitespace()) i++
            val duration = text.substring(durationStart, i).ifEmpty { null }
            if (duration == null || !isDuration(duration)) {
                info(M3uDiagnosticCodes.INVALID_DURATION, line, "Invalid #EXTINF duration")
            }
            val parsed = AttributeTokenizer.parse(text, i, expectTitle = true, maxAttributes = limits.maxAttributes)
            reportAttributeIssues(parsed, line, text)
            if (parsed.title == null) info(M3uDiagnosticCodes.MISSING_TITLE, line, "#EXTINF has no title")
            return Pending(line, duration, parsed.attributes, parsed.title)
        }

        private fun reportAttributeIssues(parsed: AttributeParse, line: Long, text: String) {
            if (parsed.unterminatedQuote) warning(M3uDiagnosticCodes.UNTERMINATED_QUOTE, line, "Unterminated quoted attribute value", text)
            if (parsed.malformedTokens > 0) info(M3uDiagnosticCodes.MALFORMED_ATTRIBUTE, line, "Malformed attribute token", text)
            if (parsed.duplicateKeys > 0) info(M3uDiagnosticCodes.DUPLICATE_ATTRIBUTE, line, "Duplicate attribute; last value kept")
            if (parsed.droppedAttributes > 0) {
                warning(M3uDiagnosticCodes.TOO_MANY_ATTRIBUTES, line, "Attributes beyond the limit were dropped")
            }
        }

        private fun flushPendingWithoutUrl() {
            val p = pending ?: return
            pending = null
            warning(M3uDiagnosticCodes.EXTINF_WITHOUT_URL, p.line, "#EXTINF without a stream URL was skipped", p.title)
        }

        private fun info(code: String, line: Long?, message: String, sample: String? = null) =
            report(DiagnosticSeverity.INFO, code, line, message, sample)

        private fun warning(code: String, line: Long?, message: String, sample: String? = null) =
            report(DiagnosticSeverity.WARNING, code, line, message, sample)

        private fun error(code: String, line: Long?, message: String, sample: String? = null) =
            report(DiagnosticSeverity.ERROR, code, line, message, sample)

        private fun report(severity: DiagnosticSeverity, code: String, line: Long?, message: String, sample: String?) {
            diagnostics.report(
                ImportDiagnostic(
                    unit = null,
                    severity = severity,
                    code = code,
                    location = line?.let { DiagnosticLocation(line = it) },
                    message = message,
                    sampleRedacted = sample?.let { Redactor.STRUCTURAL.redactText(it.take(SAMPLE_CHARS)) },
                ),
            )
        }
    }

    private companion object {
        const val SAMPLE_CHARS = 200
        val HLS_TAGS = listOf(
            "#EXT-X-TARGETDURATION",
            "#EXT-X-STREAM-INF",
            "#EXT-X-MEDIA-SEQUENCE",
            "#EXT-X-PLAYLIST-TYPE",
            "#EXT-X-ENDLIST",
            "#EXT-X-MAP",
            "#EXT-X-KEY",
            "#EXT-X-VERSION",
        )

        fun isHlsTag(text: String): Boolean = HLS_TAGS.any { text.startsWith(it, ignoreCase = true) }

        fun isDuration(value: String): Boolean {
            val body = value.removePrefix("-")
            if (body.isEmpty() || body.startsWith('.') || body.endsWith('.')) return false
            return body.count { it == '.' } <= 1 && body.all { it.isDigit() || it == '.' }
        }

        fun keyValue(text: String): Pair<String, String>? {
            val eq = text.indexOf('=')
            if (eq <= 0) return null
            return text.substring(0, eq).trim().lowercase() to text.substring(eq + 1).trim()
        }

        /** Kodi-style `User-Agent=x&Referer=y` after `|`; values are percent-decoded once. */
        fun parsePipeHeaders(text: String): Map<String, String> = text.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else part.substring(0, eq).trim() to PercentEncoding.decode(part.substring(eq + 1).trim())
        }.toMap(LinkedHashMap())
    }
}
