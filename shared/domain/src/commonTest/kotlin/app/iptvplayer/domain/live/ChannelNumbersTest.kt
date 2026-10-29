package app.iptvplayer.domain.live

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelNumbersTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000_000_000_000L

    private fun numbers(table: Map<String, ChannelNumbers.Assigned>) = table.mapValues { it.value.number }

    @Test
    fun firstAssignmentUsesTheProvidersNumbers() {
        val table = ChannelNumbers.assign(emptyMap(), listOf("a" to 5, "b" to 1, "c" to null), now)
        assertEquals(mapOf("a" to 5, "b" to 1, "c" to 2), numbers(table))
    }

    @Test
    fun duplicateProviderNumbersFallBackToOrder() {
        val table = ChannelNumbers.assign(emptyMap(), listOf("a" to 3, "b" to 3, "c" to 1), now)
        assertEquals(mapOf("c" to 1, "a" to 2, "b" to 3), numbers(table))
    }

    @Test
    fun aReshuffleKeepsEveryNumber() {
        val first = ChannelNumbers.assign(emptyMap(), listOf("a" to null, "b" to null, "c" to null), now)
        val later = ChannelNumbers.assign(first, listOf("c" to 1, "a" to 2, "b" to 3), now + day)
        assertEquals(numbers(first), numbers(later))
    }

    @Test
    fun aNewChannelGetsTheNextFreeNumber() {
        val first = ChannelNumbers.assign(emptyMap(), listOf("a" to null, "b" to null), now)
        assertEquals(3, ChannelNumbers.assign(first, listOf("a" to null, "new" to 1, "b" to null), now)["new"]?.number)
    }

    @Test
    fun aChannelThatComesBackWithin90DaysGetsItsOldNumber() {
        val first = ChannelNumbers.assign(emptyMap(), listOf("a" to null, "b" to null), now)
        val gone = ChannelNumbers.assign(first, listOf("a" to null), now + day)
        assertEquals(2, ChannelNumbers.assign(gone, listOf("a" to null, "b" to null), now + 30 * day)["b"]?.number)
        val longGone = ChannelNumbers.assign(gone, listOf("a" to null), now + 100 * day)
        assertEquals(null, longGone["b"])
    }
}
