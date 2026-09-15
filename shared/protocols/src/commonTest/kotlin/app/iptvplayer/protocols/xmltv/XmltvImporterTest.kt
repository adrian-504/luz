package app.iptvplayer.protocols.xmltv

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.Program
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.fixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class XmltvImporterTest {
    private val source = EpgSourceId("0c9a6c1e-2f4b-4d7e-9a51-3b8d7e6f5a02")

    private class Imported(val result: XmltvImportResult, val items: List<ContentItem>) {
        val programmes: List<Program> get() = items.filterIsInstance<ContentItem.ProgramItem>().map { it.program }
        val channels get() = items.filterIsInstance<ContentItem.EpgChannelItem>().map { it.channel }
        fun codes() = result.diagnosticCounts.keys
    }

    private fun import(bytes: ByteArray, options: XmltvImportOptions = XmltvImportOptions(source)): Imported {
        val items = ArrayList<ContentItem>()
        val result = XmltvImporter.import(ByteArraySource(bytes), options) { items += it }
        return Imported(result, items)
    }

    private fun utc(text: String) = Instant.parse(text)

    @Test
    fun smallValidMatchesManifestExpectations() {
        val imported = import(Fixtures.smallValidXmltv)
        assertEquals(ImportStatus.PUBLISHED, imported.result.status)
        assertFalse(imported.result.compressed)
        assertEquals(3, imported.channels.size)
        assertEquals(listOf("Example News", "Exemple Infos"), imported.channels[0].displayNames.map { it.text })
        assertEquals(8, imported.programmes.size)
        assertEquals(0, imported.result.counts.droppedProgrammes)

        val sports = imported.programmes.filter { it.epgChannelKey.channelId == "sports1.example" }.minBy { it.start }
        assertEquals(utc("2026-09-14T17:00:00Z"), sports.start)
        assertTrue(sports.flags.isPremiere)
        assertEquals("Matchday 5", sports.subtitle)

        val kids = imported.programmes.filter { it.epgChannelKey.channelId == "kids.example" }.minBy { it.start }
        assertEquals(2, kids.episode?.season)
        assertEquals(5, kids.episode?.episode)
        assertEquals("S02E05", kids.episode?.onScreen)

        val special = imported.programmes.single { it.title.startsWith("Example <") }
        assertEquals("Example <Special> Report", special.title)
        assertEquals("Numeric character reference: café — done.", special.description)
        assertTrue(special.flags.previouslyShown)

        val morning = imported.programmes.first { it.title == "Example Morning News" }
        assertEquals(listOf("News"), morning.categories)
        assertTrue(morning.flags.isNew && morning.flags.isLive)
        assertEquals("Synthetic description for a fixture programme & nothing more.", morning.description)
        assertEquals(1, imported.items.filterIsInstance<ContentItem.ProgramItem>().first { it.program == morning }.artwork.size)
        assertEquals("GB", imported.programmes.first { it.title == "Example Midday Bulletin" }.rating?.system)

        assertEquals(
            1,
            imported.programmes.count {
                it.epgChannelKey.channelId == "unlinked.example"
            },
            "programmes for undefined channels are kept",
        )
        assertTrue("XMLTV_XML_DOCTYPE_SKIPPED" in imported.codes(), "external DTD reference is skipped, never fetched")
    }

    @Test
    fun gzipBodyGivesTheSameResult() {
        val plain = import(Fixtures.smallValidXmltv)
        val gz = import(Fixtures.smallValidXmltvGz)
        assertTrue(gz.result.compressed)
        assertEquals(ImportStatus.PUBLISHED, gz.result.status)
        assertEquals(plain.programmes, gz.programmes)
    }

    @Test
    fun timezoneVariantsNormalizeToManifestExpectations() {
        val imported = import(Fixtures.timezoneVariantsXmltv)
        val tz = imported.programmes.filter { it.epgChannelKey.channelId == "tz.example" }
        val expected = listOf(
            Triple("P01", "2026-09-14T06:00:00Z", "2026-09-14T07:00:00Z"),
            Triple("P02", "2026-09-14T07:00:00Z", "2026-09-14T08:00:00Z"),
            Triple("P03", "2026-09-14T08:00:00Z", "2026-09-14T09:00:00Z"),
            Triple("P04", "2026-09-14T09:00:00Z", "2026-09-14T10:00:00Z"),
            Triple("P05", "2026-09-14T10:00:00Z", "2026-09-14T11:00:00Z"),
            Triple("P06", "2026-09-14T11:00:00Z", "2026-09-14T12:00:00Z"),
            Triple("P07", "2026-09-14T12:00:00Z", "2026-09-14T12:30:00Z"),
            Triple("P08", "2026-09-14T12:30:00Z", "2026-09-14T13:30:00Z"),
            Triple("P10", "2026-09-14T14:30:00Z", "2026-09-14T15:30:00Z"),
            Triple("P11", "2026-10-25T00:00:00Z", "2026-10-25T01:00:00Z"),
        )
        assertEquals(expected, tz.map { Triple(it.title.take(3), it.start.toString(), it.end.toString()) })
        assertTrue(imported.programmes.none { it.title.startsWith("P09") || it.title.startsWith("P12") })
        for (code in listOf(
            XmltvDiagnosticCodes.NON_POSITIVE_DURATION,
            XmltvDiagnosticCodes.OVERLAP_TRUNCATED,
            XmltvDiagnosticCodes.MISSING_STOP,
        )) {
            assertTrue(code in imported.codes(), "missing $code")
        }
        assertEquals(2, imported.result.counts.droppedProgrammes)
    }

    @Test
    fun malformedDocumentKeepsWhatItCanAndIsPartial() {
        val imported = import(Fixtures.malformedXmltv)
        assertEquals(ImportStatus.PARTIAL, imported.result.status)
        val titles = imported.programmes.map { it.title }
        assertTrue(titles.containsAll(listOf("M01 valid before errors", "M05 valid after errors", "M07 valid")), titles.toString())
        assertFalse(titles.any { it.startsWith("M02") || it.startsWith("M03") || it.startsWith("M08") }, titles.toString())
        assertTrue(XmltvDiagnosticCodes.BAD_TIMESTAMP in imported.codes())
        assertTrue(XmltvDiagnosticCodes.MISSING_PROGRAMME_CHANNEL in imported.codes())
        assertTrue(XmltvDiagnosticCodes.TRUNCATED_DOCUMENT in imported.codes())
    }

    @Test
    fun externalEntitiesAreNeverResolved() {
        val imported = import(Fixtures.xxeXmltv)
        assertEquals(ImportStatus.PUBLISHED, imported.result.status)
        assertEquals(1, imported.programmes.size)
        assertEquals("Safe Title", imported.programmes[0].title)
        assertNull(imported.programmes[0].description, "the &remote; reference produced nothing")
        assertTrue(imported.channels.single().displayNames.isEmpty(), "the &xxe; reference produced nothing")
        assertTrue("XMLTV_XML_UNDEFINED_ENTITY" in imported.codes())
        val everything = imported.items.joinToString() + imported.result.diagnostics.joinToString()
        assertFalse(everything.contains("root:") || everything.contains("attacker"), everything)
    }

    @Test
    fun entityExpansionIsNeutralized() {
        val imported = import(Fixtures.entityExpansionXmltv)
        assertEquals(ImportStatus.PUBLISHED, imported.result.status)
        assertTrue(imported.channels.single().displayNames.isEmpty())
        assertFalse(imported.items.joinToString().contains("lol"))
    }

    @Test
    fun preferredLanguagesTimeShiftAndRetention() {
        val french = import(Fixtures.smallValidXmltv, XmltvImportOptions(source, preferredLanguages = listOf("fr")))
        assertNotNull(french.programmes.firstOrNull { it.title == "Exemple Matin" })

        val shifted = import(Fixtures.smallValidXmltv, XmltvImportOptions(source, timeShiftMinutes = -60))
        assertEquals(utc("2026-09-14T04:00:00Z"), shifted.programmes.first { it.title == "Example Morning News" }.start)

        val window = utc("2026-09-14T10:30:00Z")..utc("2026-09-14T17:30:00Z")
        val retained = import(Fixtures.smallValidXmltv, XmltvImportOptions(source, retention = window))
        assertEquals(
            setOf("Example Midday Bulletin", "Example <Special> Report", "Example Football Live"),
            retained.programmes.map {
                it.title
            }.toSet(),
        )
        assertEquals(5, retained.result.counts.outsideRetention)
    }

    @Test
    fun overlapsDuplicatesClampingAndOutOfOrderInput() {
        val xml = """<tv>
            <programme start="20260914100000 +0000" stop="20260914110000 +0000" channel="c"><title>A</title></programme>
            <programme start="20260914100000 +0000" stop="20260914110000 +0000" channel="c"><title>A</title></programme>
            <programme start="20260914110000 +0000" stop="20260916110000 +0000" channel="c"><title>Long</title></programme>
            <programme start="20260914090000 +0000" stop="20260914093000 +0000" channel="c"><title>Early</title></programme>
            <programme start="20260914080000 +0000" channel="c"><title>EarlyNoStop</title></programme>
            <programme start="bad" stop="20260914093000 +0000" channel="c"><title>Bad</title></programme>
            <programme start="20260914120000 +0000" stop="20260914130000 +0000" channel="c"><title>   </title></programme>
        </tv>"""
        val imported = import(xml.encodeToByteArray())
        assertEquals(
            listOf("A", "Early", "Long"),
            imported.programmes.map {
                it.title
            },
            "A is emitted when Long arrives; Early is out of order and emitted immediately; Long at the end",
        )
        val long = imported.programmes.last()
        assertEquals(utc("2026-09-15T11:00:00Z"), long.end, "clamped to 24 h")
        for (code in listOf(
            XmltvDiagnosticCodes.DUPLICATE_PROGRAMME,
            XmltvDiagnosticCodes.DURATION_CLAMPED,
            XmltvDiagnosticCodes.OUT_OF_ORDER,
            XmltvDiagnosticCodes.BAD_TIMESTAMP,
            XmltvDiagnosticCodes.MISSING_TITLE,
        )) {
            assertTrue(code in imported.codes(), "missing $code")
        }
    }

    @Test
    fun nonGuideBodiesAreRejected() {
        assertEquals(DomainError.Validation(ValidationFailure.UNEXPECTED_CONTENT_TYPE_HTML), import(Fixtures.htmlErrorBody).result.error)
        assertEquals(DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT), import(Fixtures.authSuccessJson).result.error)
        assertEquals(DomainError.Validation(ValidationFailure.EMPTY_RESPONSE), import(ByteArray(0)).result.error)
        assertEquals(DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT), import(Fixtures.smallValidM3u).result.error)
        assertEquals(ImportStatus.FAILED, import(Fixtures.htmlErrorBody).result.status)
    }

    @Test
    fun timestampParsing() {
        assertEquals(utc("2026-09-14T06:00:00Z"), XmltvTime.parse("20260914060000 +0000"))
        assertEquals(utc("2026-09-14T11:00:00Z"), XmltvTime.parse("20260914060000 -0500"))
        assertEquals(utc("2026-09-14T00:30:00Z"), XmltvTime.parse("202609140600 +0530"))
        assertEquals(utc("2024-02-29T23:59:59Z"), XmltvTime.parse("20240229235959"))
        assertEquals(utc("1999-12-31T23:00:00Z"), XmltvTime.parse("20000101000000 +0100"))
        for (bad in listOf(
            null,
            "",
            "2026-09-14T06:00:00Z",
            "20250229000000",
            "20261301000000",
            "20260914246000",
            "20260914060000 +25x0",
            "2026091406",
        )) {
            assertNull(XmltvTime.parse(bad), bad)
        }
    }
}
