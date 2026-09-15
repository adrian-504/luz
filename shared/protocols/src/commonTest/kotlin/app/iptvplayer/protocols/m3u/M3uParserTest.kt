package app.iptvplayer.protocols.m3u

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.CollectingDiagnosticSink
import app.iptvplayer.domain.ports.ParseLimits
import app.iptvplayer.domain.ports.ParseOutcome
import app.iptvplayer.protocols.fixtures.Fixtures
import app.iptvplayer.protocols.sniff.ContentSniffer
import app.iptvplayer.protocols.sniff.SniffedFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {
    private class Parsed(val outcome: ParseOutcome, val records: List<M3uRecord>, val diagnostics: CollectingDiagnosticSink) {
        val entries get() = records.filterIsInstance<M3uRecord.Entry>()
        val headers get() = records.filterIsInstance<M3uRecord.Header>()
        fun codes() = diagnostics.countsByCode.keys
    }

    private fun parse(bytes: ByteArray, limits: ParseLimits = ParseLimits.M3U): Parsed {
        val records = ArrayList<M3uRecord>()
        val diagnostics = CollectingDiagnosticSink()
        val outcome = M3uParser().parse(ByteArraySource(bytes), limits, { records.add(it) }, diagnostics)
        return Parsed(outcome, records, diagnostics)
    }

    private fun parse(text: String, limits: ParseLimits = ParseLimits.M3U) = parse(text.encodeToByteArray(), limits)

    @Test
    fun attributesInAnyOrderQuotedUnquotedAndCommaInTitle() {
        val result = AttributeTokenizer.parse(
            "#EXTINF:0 tvg-id=sports1.example group-title='Sports' TVG-NAME=\"Demo, Sports 1\",Demo Sports 1, Live",
            10,
            expectTitle = true,
            maxAttributes = 64,
        )
        assertEquals(mapOf("tvg-id" to "sports1.example", "group-title" to "Sports", "tvg-name" to "Demo, Sports 1"), result.attributes)
        assertEquals("Demo Sports 1, Live", result.title)
        assertEquals(0, result.malformedTokens + result.duplicateKeys + result.droppedAttributes)
    }

    @Test
    fun tokenizerRecoversFromDuplicatesBareWordsAndUnterminatedQuotes() {
        val dup = AttributeTokenizer.parse(" a=\"1\" a=\"2\" bare b=3,T", 0, expectTitle = true, maxAttributes = 64)
        assertEquals(mapOf("a" to "2", "b" to "3"), dup.attributes)
        assertEquals(1, dup.duplicateKeys)
        assertEquals(1, dup.malformedTokens)
        assertEquals("T", dup.title)

        val unterminated = AttributeTokenizer.parse(" tvg-id=\"x group=a,Title", 0, expectTitle = true, maxAttributes = 64)
        assertTrue(unterminated.unterminatedQuote)
        assertEquals("Title", unterminated.title)

        val limited = AttributeTokenizer.parse(" a=1 b=2 c=3,T", 0, expectTitle = true, maxAttributes = 2)
        assertEquals(setOf("a", "b"), limited.attributes.keys)
        assertEquals(1, limited.droppedAttributes)

        assertNull(AttributeTokenizer.parse(" a=1 b=2", 0, expectTitle = true, maxAttributes = 8).title)
    }

    @Test
    fun flatJsonForExtHttp() {
        assertEquals(
            mapOf("User-Agent" to "UA/3.0", "Cookie" to "s=\"q\"", "n" to "5"),
            FlatJsonObject.parse("""{"User-Agent":"UA/3.0", "Cookie" : "s=\"q\"", "n": 5}"""),
        )
        assertEquals(emptyMap(), FlatJsonObject.parse(" {} "))
        for (bad in listOf("", "[]", "{\"a\":{\"b\":1}}", "{\"a\":\"1\"", "{\"a\" \"1\"}", "{\"a\":\"1\"} trailing")) {
            assertNull(FlatJsonObject.parse(bad), bad)
        }
    }

    @Test
    fun smallValidFixture() {
        val result = parse(Fixtures.smallValidM3u)
        assertEquals(ParseOutcome.Completed, result.outcome)
        assertEquals(5, result.entries.size)
        assertEquals("https://epg.example.org/small-valid.xml", result.headers.single().attributes["url-tvg"])
        assertEquals(
            listOf("Example News", "Demo Sports 1", "Example Kids", "Example Replay", "Example Movie (2025)"),
            result.entries.map {
                it.title
            },
        )
        assertEquals("2", result.entries[3].attributes["catchup-days"])
        assertEquals(0, result.diagnostics.total, result.diagnostics.diagnostics.toString())
    }

    @Test
    fun unusualAttributesFixture() {
        val result = parse(Fixtures.unusualAttributesM3u)
        assertEquals(ParseOutcome.Completed, result.outcome)
        val entries = result.entries
        assertEquals(9, entries.size)
        assertEquals("+1", result.headers.single().attributes["tvg-shift"], "BOM and CRLF must not break the header")

        val sports = entries[1]
        assertEquals("Demo Sports 1, Live", sports.title)
        assertEquals("sports1.example", sports.attributes["tvg-id"])
        assertEquals("Sports", sports.attributes["group-title"])
        assertEquals(mapOf("http-user-agent" to "ExamplePlayer/1.0", "http-referrer" to "https://www.example.com/"), sports.vlcOptions)

        val kids = entries[2]
        assertEquals("kids.example", kids.attributes["tvg-id"], "uppercase keys are lowercased")
        assertEquals("https://cdn.example.net/live/kids.m3u8", kids.url)
        assertEquals(mapOf("User-Agent" to "ExampleUA/2.0", "Referer" to "https://www.example.com/"), kids.pipeHeaders)

        assertEquals("Documentary", entries[3].extGroup)
        assertEquals("yes", entries[3].attributes["x-custom-flag"])
        assertEquals("session=canary-cookie-value", entries[4].httpHeaders["Cookie"])
        assertEquals("com.widevine.alpha", entries[4].kodiProps["inputstream.adaptive.license_type"])
        assertEquals("5400.5", entries[6].durationRaw)
        assertEquals("Ünïcødé Kanal — 東京 テスト", entries[8].title)
        assertFalse(M3uDiagnosticCodes.INVALID_UTF8 in result.codes())
    }

    @Test
    fun malformedFixtureNeverAbortsAndReportsEveryProblem() {
        val result = parse(Fixtures.malformedM3u)
        assertEquals(ParseOutcome.Completed, result.outcome)
        val titles = result.entries.map { it.title }
        assertTrue("Valid Channel One" in titles && "Valid Channel Two" in titles)
        assertEquals(2, titles.count { it == "Duplicate Channel" }, "parser keeps duplicates; the normalizer collapses them")
        assertTrue(result.entries.any { it.url.endsWith("bare-url-without-extinf.m3u8") && it.title == null })
        assertFalse(result.entries.any { it.url.endsWith("longline.m3u8") }, "URL of the skipped over-long line is dropped")
        assertTrue(result.entries.any { it.url == "javascript:alert(1)" }, "scheme policy is the normalizer's job")
        for (code in listOf(
            M3uDiagnosticCodes.EXTINF_WITHOUT_URL,
            M3uDiagnosticCodes.URL_WITHOUT_EXTINF,
            M3uDiagnosticCodes.MISSING_TITLE,
            M3uDiagnosticCodes.INVALID_DURATION,
            M3uDiagnosticCodes.INVALID_UTF8,
            M3uDiagnosticCodes.LINE_TOO_LONG,
            M3uDiagnosticCodes.URL_OF_DROPPED_ENTRY,
        )) {
            assertTrue(code in result.codes(), "missing $code in ${result.codes()}")
        }
        assertEquals(2, result.diagnostics.countsByCode[M3uDiagnosticCodes.EXTINF_WITHOUT_URL], "mid-file and at EOF")
    }

    @Test
    fun hlsPlaylistsAreRejected() {
        assertEquals(ParseOutcome.Rejected(ValidationFailure.SOURCE_IS_HLS_PLAYLIST), parse(Fixtures.hlsDisguisedM3u).outcome)
        assertEquals(SniffedFormat.HLS_PLAYLIST, ContentSniffer.sniff(Fixtures.hlsDisguisedM3u))
        assertEquals(SniffedFormat.HLS_PLAYLIST, ContentSniffer.sniff(Fixtures.liveMediaM3u8))
    }

    @Test
    fun missingHeaderIsAWarningNotAFailure() {
        val result = parse("#EXTINF:-1,A\nhttp://cdn.example.net/a.ts\n")
        assertEquals(1, result.entries.size)
        assertTrue(M3uDiagnosticCodes.MISSING_HEADER in result.codes())
    }

    @Test
    fun limitsStopParsingWithOutcome() {
        val many = "#EXTM3U\n" + (1..10).joinToString("") { "#EXTINF:-1,C$it\nhttp://cdn.example.net/$it.ts\n" }
        val records = parse(many, ParseLimits.M3U.copy(maxRecords = 3))
        assertEquals(ParseOutcome.Stopped(LimitKind.RECORD_COUNT), records.outcome)
        assertEquals(3, records.entries.size)
        assertEquals(ParseOutcome.Stopped(LimitKind.DOWNLOAD_SIZE), parse(many, ParseLimits.M3U.copy(maxBytes = 40)).outcome)
    }

    @Test
    fun sniffingRecognizesCommonBodies() {
        assertEquals(SniffedFormat.M3U, ContentSniffer.sniff(Fixtures.unusualAttributesM3u))
        assertEquals(SniffedFormat.XMLTV, ContentSniffer.sniff(Fixtures.smallValidXmltv))
        assertEquals(SniffedFormat.GZIP, ContentSniffer.sniff(Fixtures.smallValidXmltvGz))
        assertEquals(SniffedFormat.HTML, ContentSniffer.sniff(Fixtures.htmlErrorBody))
        assertEquals(SniffedFormat.JSON, ContentSniffer.sniff(Fixtures.authSuccessJson))
        assertEquals(SniffedFormat.EMPTY, ContentSniffer.sniff(" \n\t".encodeToByteArray()))
        assertEquals(SniffedFormat.UNKNOWN, ContentSniffer.sniff("http://cdn.example.net/a.ts".encodeToByteArray()))
    }
}
