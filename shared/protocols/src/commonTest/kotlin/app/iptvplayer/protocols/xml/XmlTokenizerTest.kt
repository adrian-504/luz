package app.iptvplayer.protocols.xml

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.protocols.xml.XmlTokenizer.Token
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmlTokenizerTest {
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

    private fun tokens(
        bytes: ByteArray,
        chunk: Int = 1 shl 16,
        buffer: Int = 64,
        maxDepth: Int = 16,
        maxAttributes: Int = 8,
        maxText: Int = 1024,
        maxTotal: Long = Long.MAX_VALUE,
    ): Pair<List<Token>, List<XmlIssue>> {
        val issues = ArrayList<XmlIssue>()
        val tokenizer = XmlTokenizer(Chunked(bytes, chunk), maxTotal, maxDepth, maxAttributes, maxText, { issues += it }, buffer)
        val out = ArrayList<Token>()
        while (true) {
            val token = tokenizer.next()
            out += token
            if (token is Token.EndOfDocument || token is Token.Stopped) return out to issues
        }
    }

    private fun tokens(text: String, chunk: Int = 1 shl 16) = tokens(text.encodeToByteArray(), chunk)

    @Test
    fun elementsAttributesTextAcrossChunkBoundaries() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><!-- c --><tv a="1" b='two'>""" +
            """<x y="&lt;&amp;&#x41;&#66;"/>caf&#233; &quot;ok&quot;<![CDATA[<raw & text>]]></tv>"""
        val expected = listOf(
            Token.Start("tv", mapOf("a" to "1", "b" to "two")),
            Token.Start("x", mapOf("y" to "<&AB")),
            Token.End("x"),
            Token.Text("café \"ok\"<raw & text>"),
            Token.End("tv"),
            Token.EndOfDocument(false),
        )
        for (chunk in listOf(1, 2, 3, 5, 8, 1000)) {
            val (result, issues) = tokens(xml, chunk)
            assertEquals(expected, result, "chunk=$chunk")
            assertTrue(issues.isEmpty(), issues.toString())
        }
    }

    @Test
    fun doctypeWithInternalSubsetIsSkippedAndEntitiesAreNeverExpanded() {
        val xml = """<!DOCTYPE tv [ <!ENTITY a "x"> <!ENTITY b SYSTEM "file:///etc/passwd"> %p; ]><tv>pre&a;&b;post</tv>"""
        val (result, issues) = tokens(xml)
        assertEquals(listOf(Token.Start("tv", emptyMap()), Token.Text("prepost"), Token.End("tv"), Token.EndOfDocument(false)), result)
        assertEquals(listOf(XmlIssue.DOCTYPE_SKIPPED, XmlIssue.UNDEFINED_ENTITY, XmlIssue.UNDEFINED_ENTITY), issues)
    }

    @Test
    fun recoversFromBareAmpersandsMismatchedTagsAndStrayLessThan() {
        val (result, issues) = tokens("<tv><p><t>Tom & Jerry < 3</titel></p><q></tv>")
        assertEquals(
            listOf(
                Token.Start("tv", emptyMap()), Token.Start("p", emptyMap()), Token.Start("t", emptyMap()),
                Token.Text("Tom & Jerry < 3"), Token.End("t"), Token.End("p"), Token.Start("q", emptyMap()),
                Token.End("q"), Token.End("tv"), Token.EndOfDocument(false),
            ),
            result,
        )
        assertTrue(
            XmlIssue.BARE_AMPERSAND in issues && XmlIssue.MALFORMED_TAG in issues && XmlIssue.MISMATCHED_END_TAG in issues,
            issues.toString(),
        )
    }

    @Test
    fun truncatedDocumentsReportOpenElements() {
        val (result, _) = tokens("<tv><programme><title>cut")
        assertEquals(Token.EndOfDocument(true), result.last())
        assertEquals(Token.Text("cut"), result[result.lastIndex - 1])
    }

    @Test
    fun invalidCharacterReferencesAndUtf8AreReported() {
        val (result, issues) = tokens("<a>&#0;&#xD800;ok</a>")
        assertEquals(Token.Text("ok"), result[1])
        assertEquals(listOf(XmlIssue.INVALID_CHARACTER_REFERENCE, XmlIssue.INVALID_CHARACTER_REFERENCE), issues)
        val bad = "<a>".encodeToByteArray() + byteArrayOf(0x41, 0xFF.toByte()) + "</a>".encodeToByteArray()
        val (badTokens, badIssues) = tokens(bad)
        assertEquals("A" + Char(0xFFFD), (badTokens[1] as Token.Text).text)
        assertEquals(listOf(XmlIssue.INVALID_UTF8), badIssues)
    }

    @Test
    fun latin1DeclaredDocumentsAreTranscoded() {
        val bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a v=\"".encodeToByteArray() + byteArrayOf(0xE9.toByte()) +
            "\">".encodeToByteArray() + byteArrayOf(0xFC.toByte()) + "&#233;</a>".encodeToByteArray()
        val (result, issues) = tokens(bytes)
        assertEquals(Token.Start("a", mapOf("v" to "é")), result[0])
        assertEquals(Token.Text("üé"), result[1])
        assertTrue(issues.isEmpty())
    }

    @Test
    fun limitsStopTokenizing() {
        assertEquals(Token.Stopped(LimitKind.DEPTH), tokens("<a><b><c><d></d></c></b></a>".encodeToByteArray(), maxDepth = 3).first.last())
        assertEquals(
            Token.Stopped(LimitKind.ATTRIBUTE_COUNT),
            tokens("<a x1='1' x2='2' x3='3'/>".encodeToByteArray(), maxAttributes = 2).first.last(),
        )
        assertEquals(
            Token.Stopped(LimitKind.TEXT_LENGTH),
            tokens("<a>${"x".repeat(100)}</a>".encodeToByteArray(), maxText = 50).first.last(),
        )
        assertEquals(
            Token.Stopped(LimitKind.TEXT_LENGTH),
            tokens("<a v='${"x".repeat(100)}'/>".encodeToByteArray(), maxText = 50).first.last(),
        )
        assertEquals(
            Token.Stopped(LimitKind.DECOMPRESSED_SIZE),
            tokens("<a>${"x".repeat(100)}</a>".encodeToByteArray(), maxTotal = 20).first.last(),
        )
    }

    @Test
    fun billionLaughsCostsNothing() {
        val lol = buildString {
            append("<!DOCTYPE tv [<!ENTITY lol \"lol\">")
            for (i in 1..9) append("<!ENTITY lol$i \"${"&lol${if (i == 1) "" else i - 1};".repeat(10)}\">")
            append("]><tv><n>&lol9;</n></tv>")
        }
        val (result, issues) = tokens(lol)
        assertEquals(
            listOf(
                Token.Start("tv", emptyMap()),
                Token.Start("n", emptyMap()),
                Token.End("n"),
                Token.End("tv"),
                Token.EndOfDocument(false),
            ),
            result,
        )
        assertEquals(listOf(XmlIssue.DOCTYPE_SKIPPED, XmlIssue.UNDEFINED_ENTITY), issues)
    }

    @Test
    fun mutatedDocumentsNeverThrow() {
        val random = Random(20260914)
        val seed = """<?xml version="1.0"?><!DOCTYPE tv SYSTEM "x.dtd"><tv><programme start="20260914060000 +0000" channel="a">""" +
            """<title lang="en">A &amp; B</title><![CDATA[x]]><!-- c --><icon src="https://img.example.com/a.png"/></programme></tv>"""
        repeat(800) {
            val bytes = seed.encodeToByteArray().toMutableList()
            repeat(random.nextInt(1, 15)) {
                when (random.nextInt(3)) {
                    0 -> if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = "<>/&;\"'=![]?-#x".random(random).code.toByte()
                    1 -> if (bytes.isNotEmpty()) bytes.removeAt(random.nextInt(bytes.size))
                    else -> bytes.add(random.nextInt(bytes.size + 1), random.nextInt(256).toByte())
                }
            }
            tokens(bytes.toByteArray(), chunk = random.nextInt(1, 32), buffer = random.nextInt(1, 64))
        }
    }
}
