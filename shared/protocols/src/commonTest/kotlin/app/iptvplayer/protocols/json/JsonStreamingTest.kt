package app.iptvplayer.protocols.json

import app.iptvplayer.domain.error.LimitKind
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.ByteSource
import app.iptvplayer.protocols.json.JsonArrayStreamer.Outcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonStreamingTest {
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

    private fun streamer(
        maxElementBytes: Int = 4096,
        maxDepth: Int = 8,
        maxElements: Long = 1000,
        maxTotal: Long = Long.MAX_VALUE,
        buffer: Int = 64,
    ) = JsonArrayStreamer(maxTotal, maxElementBytes, maxDepth, maxElements, buffer)

    private fun collect(text: String, chunk: Int = 1 shl 16, s: JsonArrayStreamer = streamer()): Pair<Outcome, List<JsonElement>> {
        val out = ArrayList<JsonElement>()
        val outcome = s.stream(Chunked(text.encodeToByteArray(), chunk)) { out += it }
        return outcome to out
    }

    @Test
    fun streamsMixedElementsAcrossArbitraryChunkBoundaries() {
        val text = """ [ {"a":"x,]}\"y","n":[1,2,{"b":null}]}, "str\\\"ing", 12.5e3, true, null, [], {} ] """
        val expected = Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray
        for (chunk in 1..9) {
            val (outcome, elements) = collect(text, chunk)
            assertEquals(Outcome.Completed(7), outcome, "chunk=$chunk")
            assertEquals(expected.toList(), elements, "chunk=$chunk")
        }
    }

    @Test
    fun emptyAndWhitespaceBodies() {
        assertEquals(Outcome.Completed(0), collect("[]").first)
        assertEquals(Outcome.Completed(0), collect("  [ \n ]  ").first)
        assertEquals(Outcome.Empty, collect("  ").first)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "[1]".encodeToByteArray()
        assertEquals(Outcome.Completed(1), streamer().stream(ByteArraySource(bom)) {})
    }

    @Test
    fun truncatedArraysDeliverCompleteElementsThenReportMalformed() {
        val (outcome, elements) = collect("""[{"id":1},{"id":2},{"id":3,"name":"Trunc""")
        assertIs<Outcome.Malformed>(outcome)
        assertEquals(2, elements.size)
        assertIs<Outcome.Malformed>(collect("""[1,2""").first)
        assertIs<Outcome.Malformed>(collect("""[1,,2]""").first)
        assertIs<Outcome.Malformed>(collect("""[1] trailing""").first)
        assertIs<Outcome.Malformed>(collect("""<html>""").first)
    }

    @Test
    fun objectBodiesAreReportedAsNotAnArray() {
        val outcome = assertIs<Outcome.NotAnArray>(collect("""{"user_info":{"auth":0}}""").first)
        assertEquals("0", ((outcome.objectElement as JsonObject)["user_info"] as JsonObject)["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun limitsStopStreaming() {
        assertEquals(
            Outcome.Stopped(LimitKind.TEXT_LENGTH, 1),
            collect("""[{"a":1},{"b":"${"x".repeat(100)}"}]""", s = streamer(maxElementBytes = 50)).first,
        )
        assertEquals(Outcome.Stopped(LimitKind.DEPTH, 0), collect("[[[[[[[[[[1]]]]]]]]]]", s = streamer(maxDepth = 5)).first)
        assertEquals(Outcome.Stopped(LimitKind.RECORD_COUNT, 3), collect("[1,2,3,4,5]", s = streamer(maxElements = 3)).first)
        assertIs<Outcome.Stopped>(collect("[" + (1..200).joinToString(",") + "]", s = streamer(maxTotal = 100)).first)
    }

    @Test
    fun documentParsingIsBounded() {
        assertEquals(
            "1",
            ((streamer().parseDocument(ByteArraySource("""{"a":1}""".encodeToByteArray())) as JsonObject)["a"])!!.jsonPrimitive.content,
        )
        assertNull(streamer(maxElementBytes = 5).parseDocument(ByteArraySource("""{"a":123456}""".encodeToByteArray())))
        assertNull(streamer(maxDepth = 3).parseDocument(ByteArraySource("""{"a":{"b":{"c":{"d":1}}}}""".encodeToByteArray())))
        assertNull(streamer().parseDocument(ByteArraySource("""{"a":""".encodeToByteArray())))
    }

    @Test
    fun randomAndMutatedInputNeverThrows() {
        val random = Random(42)
        val seed = """[{"stream_id":1,"name":"A \"q\" \\ b","category_ids":[1,"2"],"x":{"y":[true,false,null]}},{"n":-1.5e-3}]"""
        repeat(500) {
            val bytes = seed.encodeToByteArray().toMutableList()
            repeat(random.nextInt(1, 12)) {
                when (random.nextInt(3)) {
                    0 -> if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = "[]{}\",:\\x".random(random).code.toByte()
                    1 -> if (bytes.isNotEmpty()) bytes.removeAt(random.nextInt(bytes.size))
                    else -> bytes.add(random.nextInt(bytes.size + 1), random.nextInt(256).toByte())
                }
            }
            streamer().stream(Chunked(bytes.toByteArray(), random.nextInt(1, 16))) {}
            streamer().parseDocument(ByteArraySource(bytes.toByteArray()))
        }
    }

    @Test
    fun lenientAccessorsCoerceTolerantlyAndReportMismatches() {
        val mismatches = ArrayList<String>()
        val obj = LenientObject(
            Json.parseToJsonElement(
                """{"i":"12","d":7.0,"big":"9999999999","s":" x ","e":"","n":null,"b1":"1","b0":0,"bt":true,"bad":"abc",
                   "list":[1,"2",null,""],"single":"3","o":{"k":"v"},"emptyArr":[],"arr":[1],"ts":"1789362000"}""",
            ) as JsonObject,
        ) { mismatches += it }
        assertEquals(12, obj.int("i"))
        assertEquals(7L, obj.long("d"))
        assertNull(obj.int("big"))
        assertEquals("x", obj.string("s"))
        assertNull(obj.string("e"))
        assertNull(obj.string("n"))
        assertNull(obj.string("missing"))
        assertEquals(true, obj.bool("b1"))
        assertEquals(false, obj.bool("b0"))
        assertEquals(true, obj.bool("bt"))
        assertNull(obj.long("bad"))
        assertEquals(listOf("1", "2"), obj.stringList("list"))
        assertEquals(listOf("3"), obj.stringList("single"))
        assertEquals("v", obj.obj("o")?.string("k"))
        assertNull(obj.obj("emptyArr"))
        assertNull(obj.obj("arr"))
        assertEquals(1789362000L, obj.epochSeconds("ts")?.epochSeconds)
        assertNull(obj.string("o"))
        assertEquals(listOf("big", "bad", "arr", "o"), mismatches)
        assertTrue(mismatches.none { it.contains("abc") })
    }

    @Test
    fun htmlEntities() {
        assertEquals(
            "Kids & Family <HD> \"x\" 'y' é 🎬 &unknown; &",
            HtmlEntities.decode("Kids &amp; Family &lt;HD&gt; &quot;x&quot; &#39;y&#x27; &#233; &#x1F3AC; &unknown; &"),
        )
        assertEquals("no entities", HtmlEntities.decode("no entities"))
        assertEquals("&#0; &#xD800;", HtmlEntities.decode("&#0; &#xD800;"))
    }
}
