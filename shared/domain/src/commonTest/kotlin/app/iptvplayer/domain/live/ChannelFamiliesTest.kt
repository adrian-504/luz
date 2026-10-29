package app.iptvplayer.domain.live

import app.iptvplayer.domain.library.ChannelNames
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelFamiliesTest {
    private val now = 1_000_000_000_000L

    private fun copy(id: String, name: String, order: Int, tvg: String? = null) =
        ChannelFamilies.Copy(id, ChannelNames.familyKey(name), tvg, ChannelNames.display(name).quality, order)

    private val beIn = listOf(
        copy("1", "beIN Sports 1 SD", 0),
        copy("2", "beIN Sports 1 FHD", 1),
        copy("3", "beIN Sports 1 HD", 2),
        copy("4", "Other", 3),
    )

    @Test
    fun copiesBecomeOneChannel() {
        val families = ChannelFamilies.build(beIn, emptyMap(), now)
        assertEquals(listOf("2", "4"), families.collapse(listOf("1", "2", "3", "4")))
        assertEquals(listOf("2", "3", "1"), families.members.getValue(families.familyOf.getValue("1")))
    }

    @Test
    fun differentTvgIdsStaySeparate() {
        val copies = listOf(copy("a", "Info HD", 0, "one.test"), copy("b", "Info SD", 1, "two.test"))
        assertEquals(listOf("a", "b"), ChannelFamilies.build(copies, emptyMap(), now).collapse(listOf("a", "b")))
    }

    @Test
    fun chosenCopyComesFirst() {
        val families = ChannelFamilies.build(beIn, mapOf("1" to ChannelFamilies.Memory(chosen = true)), now)
        assertEquals("1", families.members.getValue(families.familyOf.getValue("2")).first())
    }

    @Test
    fun aCopyThatPlayedWellBeatsAHigherQuality() {
        val families = ChannelFamilies.build(beIn, mapOf("3" to ChannelFamilies.Memory(lastOkAt = now - 1000)), now)
        assertEquals("3", families.members.getValue(families.familyOf.getValue("2")).first())
    }

    @Test
    fun aCopyThatFailedLatelyGoesLastAndAnOldFailureIsForgotten() {
        val day = 24L * 60 * 60 * 1000
        val lately = ChannelFamilies.build(beIn, mapOf("2" to ChannelFamilies.Memory(lastFailAt = now - day)), now)
        assertEquals("2", lately.members.getValue(lately.familyOf.getValue("2")).last())
        val old = ChannelFamilies.build(beIn, mapOf("2" to ChannelFamilies.Memory(lastFailAt = now - 8 * day)), now)
        assertEquals("2", old.members.getValue(old.familyOf.getValue("2")).first())
    }

    @Test
    fun aListKeepsTheBestCopyItHolds() {
        val families = ChannelFamilies.build(beIn, emptyMap(), now)
        assertEquals(listOf("3"), families.collapse(listOf("1", "3")))
    }
}
