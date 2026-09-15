package app.iptvplayer.protocols.m3u

import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.ParseLimits
import app.iptvplayer.protocols.fixtures.Fixtures
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Mutation fuzzing: arbitrary corruption of real fixtures must never throw or exceed limits. */
class M3uRobustnessTest {
    private val seeds = listOf(Fixtures.smallValidM3u, Fixtures.unusualAttributesM3u, Fixtures.malformedM3u.copyOf(4096))
    private val tokens = listOf("#EXTINF:", "#EXTM3U", ",", "\"", "'", "=", "|", "\r", "\n", "#EXTHTTP:{", "#KODIPROP:", "\\", "%", "://")

    private fun mutate(input: ByteArray, random: Random): ByteArray {
        val bytes = input.toMutableList()
        repeat(random.nextInt(1, 40)) {
            when (random.nextInt(4)) {
                0 -> if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte()
                1 -> if (bytes.isNotEmpty()) bytes.removeAt(random.nextInt(bytes.size))
                2 -> bytes.addAll(random.nextInt(bytes.size + 1), tokens.random(random).encodeToByteArray().toList())
                else -> if (bytes.size > 2) {
                    val from = random.nextInt(bytes.size)
                    val to = minOf(bytes.size, from + random.nextInt(1, 200))
                    bytes.addAll(random.nextInt(bytes.size + 1), bytes.subList(from, to).toList())
                }
            }
        }
        return bytes.toByteArray()
    }

    @Test
    fun mutatedPlaylistsNeverThrow() {
        val random = Random(20260914)
        val limits = ParseLimits.M3U.copy(maxLineOrTokenBytes = 512, maxRecords = 200)
        var imported = 0
        repeat(300) { iteration ->
            val input = mutate(seeds[iteration % seeds.size], random)
            val result = runCatching { M3uImporter.import(ByteArraySource(input), PlaylistId("fuzz"), limits = limits) { } }
            assertTrue(result.isSuccess, "iteration $iteration threw ${result.exceptionOrNull()}")
            imported += result.getOrThrow().counts.entries
        }
        assertTrue(imported > 0, "mutations should still yield entries")
    }

    @Test
    fun randomBytesNeverThrow() {
        val random = Random(7)
        repeat(100) {
            val input = random.nextBytes(random.nextInt(0, 2048))
            M3uImporter.import(ByteArraySource(input), PlaylistId("fuzz")) { }
        }
    }
}
