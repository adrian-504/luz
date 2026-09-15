package app.iptvplayer.protocols.xmltv

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.id.ArtworkId
import app.iptvplayer.domain.id.DerivedIdKind
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.ProgramId
import app.iptvplayer.domain.id.StableIds
import app.iptvplayer.domain.model.Artwork
import app.iptvplayer.domain.model.ArtworkKind
import app.iptvplayer.domain.model.ArtworkOrigin
import app.iptvplayer.domain.model.ContentRating
import app.iptvplayer.domain.model.DisplayName
import app.iptvplayer.domain.model.DomainLimits
import app.iptvplayer.domain.model.EpgChannel
import app.iptvplayer.domain.model.EpisodeNumbering
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.Program
import app.iptvplayer.domain.model.ProgramFlags
import app.iptvplayer.domain.net.UrlCheck
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.net.UrlPolicy
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.domain.ports.CollectingDiagnosticSink
import app.iptvplayer.domain.ports.DiagnosticSeverity
import app.iptvplayer.domain.ports.ImportDiagnostic
import app.iptvplayer.domain.ports.ParseLimits
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.domain.text.TextNormalization
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.io.CorruptCompressedDataException
import app.iptvplayer.protocols.io.CountingSource
import app.iptvplayer.protocols.io.DecompressionLimitException
import app.iptvplayer.protocols.io.DecompressionLimits
import app.iptvplayer.protocols.io.PrefixReplaySource
import app.iptvplayer.protocols.io.gunzip
import app.iptvplayer.protocols.io.isGzip
import app.iptvplayer.protocols.io.readPrefix
import app.iptvplayer.protocols.sniff.ContentSniffer
import app.iptvplayer.protocols.sniff.SniffedFormat
import app.iptvplayer.protocols.xml.XmlIssue
import app.iptvplayer.protocols.xml.XmlTokenizer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Stable diagnostic codes for XMLTV import (docs/EPG.md §2, docs/IPTV_PROTOCOLS.md §5.4). */
public object XmltvDiagnosticCodes {
    public const val MISSING_CHANNEL_ID: String = "XMLTV_MISSING_CHANNEL_ID"
    public const val MISSING_PROGRAMME_CHANNEL: String = "XMLTV_MISSING_PROGRAMME_CHANNEL"
    public const val BAD_TIMESTAMP: String = "XMLTV_BAD_TIMESTAMP"
    public const val MISSING_TITLE: String = "XMLTV_MISSING_TITLE"
    public const val MISSING_STOP: String = "XMLTV_MISSING_STOP"
    public const val NON_POSITIVE_DURATION: String = "XMLTV_NON_POSITIVE_DURATION"
    public const val OVERLAP_TRUNCATED: String = "XMLTV_OVERLAP_TRUNCATED"
    public const val DURATION_CLAMPED: String = "XMLTV_DURATION_CLAMPED"
    public const val DUPLICATE_PROGRAMME: String = "XMLTV_DUPLICATE_PROGRAMME"
    public const val OUT_OF_ORDER: String = "XMLTV_OUT_OF_ORDER"
    public const val INVALID_ICON_URL: String = "XMLTV_INVALID_ICON_URL"
    public const val TRUNCATED_DOCUMENT: String = "XMLTV_TRUNCATED_DOCUMENT"
    public const val CORRUPT_COMPRESSED_DATA: String = "XMLTV_CORRUPT_COMPRESSED_DATA"

    /** Tokenizer findings, e.g. `XMLTV_XML_UNDEFINED_ENTITY` for a blocked external entity reference. */
    public const val XML_PREFIX: String = "XMLTV_XML_"
}

public data class EpgCounts(
    public val channels: Int = 0,
    public val programmes: Int = 0,
    public val droppedProgrammes: Int = 0,
    public val outsideRetention: Int = 0,
)

public class XmltvImportResult(
    /** PUBLISHED; PARTIAL when the document was truncated or malformed (programmes so far kept, §5.2); FAILED otherwise. */
    public val status: ImportStatus,
    public val error: DomainError?,
    public val compressed: Boolean,
    public val counts: EpgCounts,
    public val diagnostics: List<ImportDiagnostic>,
    public val diagnosticCounts: Map<String, Int>,
)

/** Options for one EPG source import. */
public class XmltvImportOptions(
    public val epgSourceId: EpgSourceId,
    /** User correction for mislabelled timezones (EPGSource.timeShiftMinutes). */
    public val timeShiftMinutes: Int = 0,
    /** Preferred languages for titles and descriptions, most preferred first (BCP-47 prefixes, e.g. "fr"). */
    public val preferredLanguages: List<String> = emptyList(),
    /** Programmes entirely outside this window are dropped (retention, docs/EPG.md §2 rule 8). Null keeps everything. */
    public val retention: ClosedRange<Instant>? = null,
)

/**
 * VALIDATE (gzip detection, sniffing) → PARSE (streaming tokenizer) → NORMALIZE (timezones, missing stops, overlaps,
 * durations, retention) for one XMLTV document. Memory is bounded by one pending programme per EPG channel.
 */
public object XmltvImporter {
    public fun import(
        source: ByteSource,
        options: XmltvImportOptions,
        limits: ParseLimits = ParseLimits.XMLTV,
        emit: (ContentItem) -> Unit,
    ): XmltvImportResult {
        val diagnostics = CollectingDiagnosticSink(limits.maxDiagnostics)
        val normalizer = XmltvNormalizer(options, emit) { severity, code, message ->
            diagnostics.report(ImportDiagnostic(ImportUnit.EPG, severity, code, null, message, null))
        }
        val counting = CountingSource(source, limits.maxBytes)
        var compressed = false
        fun result(status: ImportStatus, error: DomainError?) =
            XmltvImportResult(status, error, compressed, normalizer.counts, diagnostics.diagnostics, diagnostics.countsByCode)

        try {
            val rawPrefix = readPrefix(counting, ContentSniffer.SNIFF_BYTES)
            var body: ByteSource = PrefixReplaySource(rawPrefix, counting)
            if (isGzip(rawPrefix)) {
                compressed = true
                body = DecompressionLimits(gunzip(body), counting, limits.maxDecompressedBytes, limits.maxCompressionRatio)
            }
            val prefix = readPrefix(body, ContentSniffer.SNIFF_BYTES)
            when (ContentSniffer.sniff(prefix)) {
                SniffedFormat.XMLTV -> Unit
                SniffedFormat.EMPTY -> return result(ImportStatus.FAILED, DomainError.Validation(ValidationFailure.EMPTY_RESPONSE))
                SniffedFormat.HTML -> return result(
                    ImportStatus.FAILED,
                    DomainError.Validation(ValidationFailure.UNEXPECTED_CONTENT_TYPE_HTML),
                )
                else -> return result(ImportStatus.FAILED, DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT))
            }
            val tokenizer = XmlTokenizer(
                source = PrefixReplaySource(prefix, body),
                maxTotalBytes = limits.maxDecompressedBytes,
                maxDepth = limits.maxDepth,
                maxAttributes = limits.maxAttributes,
                maxTextBytes = limits.maxLineOrTokenBytes,
                onIssue = { issue -> diagnostics.report(tokenizerDiagnostic(issue)) },
            )
            val parsed = XmltvParser(tokenizer, limits.maxRecords).parse { record ->
                when (record) {
                    is XmltvRecord.Channel -> normalizer.onChannel(record)
                    is XmltvRecord.Programme -> normalizer.onProgramme(record)
                }
            }
            normalizer.finish()
            return when {
                parsed.stopped != null -> result(ImportStatus.FAILED, DomainError.Limit(parsed.stopped))
                parsed.truncated -> {
                    diagnostics.report(
                        ImportDiagnostic(
                            ImportUnit.EPG,
                            DiagnosticSeverity.ERROR,
                            XmltvDiagnosticCodes.TRUNCATED_DOCUMENT,
                            null,
                            "Document ended before all elements were closed",
                            null,
                        ),
                    )
                    result(ImportStatus.PARTIAL, DomainError.Parse(XmltvDiagnosticCodes.TRUNCATED_DOCUMENT))
                }
                else -> result(ImportStatus.PUBLISHED, null)
            }
        } catch (e: DecompressionLimitException) {
            return result(ImportStatus.FAILED, DomainError.Limit(e.limit))
        } catch (_: CorruptCompressedDataException) {
            normalizer.finish()
            diagnostics.report(
                ImportDiagnostic(
                    ImportUnit.EPG,
                    DiagnosticSeverity.ERROR,
                    XmltvDiagnosticCodes.CORRUPT_COMPRESSED_DATA,
                    null,
                    "Compressed guide data is corrupt or truncated",
                    null,
                ),
            )
            val status = if (normalizer.counts.programmes > 0) ImportStatus.PARTIAL else ImportStatus.FAILED
            return result(status, DomainError.Parse(XmltvDiagnosticCodes.CORRUPT_COMPRESSED_DATA))
        }
    }

    private fun tokenizerDiagnostic(issue: XmlIssue): ImportDiagnostic {
        val severity = when (issue) {
            XmlIssue.DOCTYPE_SKIPPED, XmlIssue.BARE_AMPERSAND -> DiagnosticSeverity.INFO
            else -> DiagnosticSeverity.WARNING
        }
        return ImportDiagnostic(
            ImportUnit.EPG,
            severity,
            XmltvDiagnosticCodes.XML_PREFIX + issue.name,
            null,
            "XML issue: ${issue.name.lowercase()}",
            null,
        )
    }
}

/** Programme normalization rules of docs/EPG.md §2, streaming per EPG channel. */
internal class XmltvNormalizer(
    private val options: XmltvImportOptions,
    private val emit: (ContentItem) -> Unit,
    private val report: (DiagnosticSeverity, String, String) -> Unit,
) {
    private class Pending(val start: Instant, val stop: Instant?, val record: XmltvRecord.Programme, val title: String)

    private val pending = LinkedHashMap<String, Pending>()
    var counts: EpgCounts = EpgCounts()
        private set

    fun onChannel(record: XmltvRecord.Channel) {
        val id = record.id?.ifEmpty { null } ?: return info(XmltvDiagnosticCodes.MISSING_CHANNEL_ID, "Channel without id skipped")
        val icon = artwork(record.iconSrc, ArtworkKind.LOGO)
        val names = record.displayNames.map { DisplayName(TextNormalization.collapse(it.text).take(DomainLimits.MAX_NAME_LENGTH), it.lang) }
        emit(ContentItem.EpgChannelItem(EpgChannel(EpgChannelKey(options.epgSourceId, id), names, icon?.id), icon))
        counts = counts.copy(channels = counts.channels + 1)
    }

    fun onProgramme(record: XmltvRecord.Programme) {
        val channel =
            record.channel?.ifEmpty { null }
                ?: return drop(XmltvDiagnosticCodes.MISSING_PROGRAMME_CHANNEL, "Programme without channel skipped")
        val start =
            XmltvTime.parse(record.start)?.shifted()
                ?: return drop(XmltvDiagnosticCodes.BAD_TIMESTAMP, "Programme with invalid start time skipped")
        val stop = if (record.stop == null) {
            null
        } else {
            XmltvTime.parse(record.stop)?.shifted() ?: run {
                info(XmltvDiagnosticCodes.BAD_TIMESTAMP, "Invalid stop time treated as missing")
                null
            }
        }
        val title = pick(record.titles)?.let { TextNormalization.collapse(it) }?.ifEmpty { null }
            ?: return drop(XmltvDiagnosticCodes.MISSING_TITLE, "Programme without title skipped")
        val next = Pending(start, stop, record, title)
        val previous = pending[channel]
        when {
            previous == null -> pending[channel] = next
            start < previous.start -> {
                // Out-of-order input cannot be corrected while streaming; keep it if it is complete on its own.
                info(XmltvDiagnosticCodes.OUT_OF_ORDER, "Programme out of chronological order; overlap correction skipped")
                if (stop !=
                    null
                ) {
                    finalize(channel, next, stop)
                } else {
                    drop(XmltvDiagnosticCodes.MISSING_STOP, "Out-of-order programme without stop skipped")
                }
            }
            start == previous.start && title == previous.title -> drop(
                XmltvDiagnosticCodes.DUPLICATE_PROGRAMME,
                "Duplicate programme collapsed",
            )
            else -> {
                val end = when {
                    previous.stop == null -> {
                        info(XmltvDiagnosticCodes.MISSING_STOP, "Missing stop derived from the next programme")
                        start
                    }
                    previous.stop > start -> {
                        info(XmltvDiagnosticCodes.OVERLAP_TRUNCATED, "Overlapping programme truncated at the next start")
                        start
                    }
                    else -> previous.stop
                }
                finalize(channel, previous, end)
                pending[channel] = next
            }
        }
    }

    fun finish() {
        for ((channel, last) in pending) {
            if (last.stop != null) {
                finalize(channel, last, last.stop)
            } else {
                drop(XmltvDiagnosticCodes.MISSING_STOP, "Last programme without stop skipped")
            }
        }
        pending.clear()
    }

    private fun finalize(channel: String, p: Pending, rawEnd: Instant) {
        if (rawEnd <= p.start) return drop(XmltvDiagnosticCodes.NON_POSITIVE_DURATION, "Programme with non-positive duration skipped")
        val end = if (rawEnd - p.start > MAX_DURATION) {
            info(XmltvDiagnosticCodes.DURATION_CLAMPED, "Programme longer than 24 h clamped")
            p.start + MAX_DURATION
        } else {
            rawEnd
        }
        options.retention?.let { window ->
            if (end <= window.start || p.start >= window.endInclusive) {
                counts = counts.copy(outsideRetention = counts.outsideRetention + 1)
                return
            }
        }
        val r = p.record
        val key = EpgChannelKey(options.epgSourceId, channel)
        val artwork = r.iconSources.mapNotNull { artwork(it, ArtworkKind.PROGRAM_ICON) }
        val program = Program(
            id = ProgramId(
                StableIds.derive(DerivedIdKind.PROGRAM, options.epgSourceId.value, listOf(channel, p.start.epochSeconds.toString())),
            ),
            epgChannelKey = key,
            start = p.start,
            end = end,
            title = p.title.take(DomainLimits.MAX_NAME_LENGTH),
            subtitle = pick(r.subTitles)?.let { TextNormalization.collapse(it) }?.take(DomainLimits.MAX_NAME_LENGTH),
            description = pick(r.descriptions)?.take(DomainLimits.MAX_DESCRIPTION_LENGTH),
            categories = r.categories.map {
                TextNormalization.collapse(
                    it.text,
                )
            }.filter { it.isNotEmpty() }.distinct().take(MAX_CATEGORIES),
            episode = episode(r.episodeNumbers),
            artwork = artwork.map { it.id },
            rating = r.rating?.let { ContentRating(it.first, it.second) },
            flags = ProgramFlags(r.isNew, r.isLive, r.isPremiere, r.previouslyShown),
            language = r.language,
        )
        emit(ContentItem.ProgramItem(program, artwork))
        counts = counts.copy(programmes = counts.programmes + 1)
    }

    /** First text in a preferred language, else the first text. */
    private fun pick(texts: List<LangText>): String? {
        for (language in options.preferredLanguages) {
            texts.firstOrNull { it.lang?.lowercase()?.startsWith(language.lowercase()) == true }?.let { return it.text }
        }
        return texts.firstOrNull()?.text
    }

    /** `xmltv_ns` is zero-based `season.episode.part` with optional `/total`; `onscreen` is kept as text. */
    private fun episode(numbers: List<Pair<String?, String>>): EpisodeNumbering? {
        if (numbers.isEmpty()) return null
        val onScreen = numbers.firstOrNull { it.first == "onscreen" }?.second
        val ns = numbers.firstOrNull { it.first == "xmltv_ns" }?.second?.split('.')
        fun part(index: Int): Int? = ns?.getOrNull(index)?.substringBefore('/')?.trim()?.toIntOrNull()?.plus(1)
        val numbering = EpisodeNumbering(part(0), part(1), part(2), onScreen)
        return numbering.takeIf { it.season != null || it.episode != null || it.part != null || it.onScreen != null }
    }

    private fun artwork(src: String?, kind: ArtworkKind): Artwork? {
        val value = src?.trim()?.ifEmpty { null } ?: return null
        val check = UrlPolicy.check(value.replace(" ", "%20"), UrlContext.ARTWORK)
        if (check !is UrlCheck.Allowed) {
            info(XmltvDiagnosticCodes.INVALID_ICON_URL, "Icon URL rejected by policy")
            return null
        }
        val template = UrlTemplate(check.url.toUrlString())
        return Artwork(
            ArtworkId(StableIds.derive(DerivedIdKind.ARTWORK, "", listOf(template.template))),
            template,
            kind,
            ArtworkOrigin.XMLTV,
            null,
            null,
        )
    }

    private fun Instant.shifted(): Instant = if (options.timeShiftMinutes == 0) this else this + options.timeShiftMinutes.minutes

    private fun info(code: String, message: String) = report(DiagnosticSeverity.INFO, code, message)

    private fun drop(code: String, message: String) {
        counts = counts.copy(droppedProgrammes = counts.droppedProgrammes + 1)
        report(DiagnosticSeverity.WARNING, code, message)
    }

    private companion object {
        val MAX_DURATION = 24.hours
        const val MAX_CATEGORIES = 16
    }
}

/** XMLTV timestamps: `YYYYMMDDhhmm[ss] [±hhmm]`; a missing offset means UTC. */
internal object XmltvTime {
    fun parse(raw: String?): Instant? {
        val value = raw?.trim() ?: return null
        val digitsEnd = value.indexOfFirst { !it.isDigit() }.let { if (it < 0) value.length else it }
        val digits = value.substring(0, digitsEnd)
        if (digits.length != 12 && digits.length != 14) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = digits.substring(8, 10).toInt()
        val minute = digits.substring(10, 12).toInt()
        val second = if (digits.length == 14) digits.substring(12, 14).toInt() else 0
        if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 || second > 60) return null
        val rest = value.substring(digitsEnd).trim()
        val offsetSeconds = when {
            rest.isEmpty() || rest == "Z" || rest.equals("UTC", ignoreCase = true) || rest.equals("GMT", ignoreCase = true) -> 0
            rest.length == 5 && (rest[0] == '+' || rest[0] == '-') && rest.substring(1).all { it.isDigit() } -> {
                val h = rest.substring(1, 3).toInt()
                val m = rest.substring(3, 5).toInt()
                if (h > 14 || m > 59) return null
                (h * 3600 + m * 60) * (if (rest[0] == '-') -1 else 1)
            }
            else -> return null
        }
        val epochDays = daysFromCivil(year, month, day)
        return Instant.fromEpochSeconds(epochDays * 86_400L + hour * 3600L + minute * 60L + second - offsetSeconds)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
