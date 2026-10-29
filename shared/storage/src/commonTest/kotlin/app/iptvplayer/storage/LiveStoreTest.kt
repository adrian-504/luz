package app.iptvplayer.storage

import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.live.ChannelNumbers
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Live TV's own memory (ADR-0052): what survives imports, and what goes with the source. */
class LiveStoreTest {
    private val file = TestDatabaseFile("live-store")
    private val driver = openIptvDatabase(file.path)
    private var now = Instant.parse("2026-10-29T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private val content = ContentStore(driver, clock)
    private val live = LiveStore(content, clock)
    private val playlist = PlaylistId("8d3f1c2b-4a5e-4f60-9b71-2c3d4e5f6a7b")

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private fun addSource() = content.addSource(
        playlist,
        ProviderId("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"),
        "Example TV",
        PlaylistType.XTREAM,
        "http://panel.example.com:8080",
        UrlTemplate("http://panel.example.com:8080"),
        CredentialRef("cred-1"),
        TransportSecurity.CLEARTEXT,
    )

    @Test
    fun theFirstImportShowsEveryGroup() {
        assertEquals(emptyList(), live.noteGroups(playlist, listOf("g1", "g2")))
        assertTrue(live.newGroups(playlist).isEmpty())
        assertTrue(content.hidden(playlist, CustomisationTarget.CHANNEL_GROUP).isEmpty())
    }

    @Test
    fun newGroupsAfterTheFirstImportAreHiddenAndMarkedNew() {
        live.noteGroups(playlist, listOf("g1"))
        assertEquals(listOf("g2"), live.noteGroups(playlist, listOf("g1", "g2")))
        assertEquals(setOf("g2"), live.newGroups(playlist))
        assertEquals(listOf("g2"), content.hidden(playlist, CustomisationTarget.CHANNEL_GROUP))
        live.clearNewGroups(playlist)
        assertTrue(live.newGroups(playlist).isEmpty())
    }

    @Test
    fun aFailureThenASuccessRanksTheCopyFirstAgain() {
        live.playedFail(playlist, "c1")
        now += 1.minutes
        live.playedOk(playlist, "c1", 900)
        val memory = live.playback(playlist).getValue("c1")
        assertTrue(memory.lastOkAt!! > memory.lastFailAt!!)
    }

    @Test
    fun choosingACopyClearsTheOthers() {
        live.choose(playlist, "c1", listOf("c1", "c2"))
        live.choose(playlist, "c2", listOf("c1", "c2"))
        val memory = live.playback(playlist)
        assertFalse(memory.getValue("c1").chosen)
        assertTrue(memory.getValue("c2").chosen)
    }

    @Test
    fun numbersAreSavedWhole() {
        live.saveChannelNumbers(playlist, mapOf("a" to ChannelNumbers.Assigned(7, 1L)))
        live.saveChannelNumbers(playlist, mapOf("b" to ChannelNumbers.Assigned(8, 2L)))
        assertEquals(mapOf("b" to ChannelNumbers.Assigned(8, 2L)), live.channelNumbers(playlist))
    }

    @Test
    fun sessionsOlderThan400DaysArePruned() {
        live.addSession(playlist, "CHANNEL", "c1", null, now.toEpochMilliseconds() - 401.days.inWholeMilliseconds, 120)
        live.addSession(playlist, "CHANNEL", "c1", null, now.toEpochMilliseconds() - 1.days.inWholeMilliseconds, 120)
        live.pruneSessions()
        assertEquals(1L, live.sessionCount(playlist))
    }

    @Test
    fun sessionsGoWithTheSource() {
        addSource()
        live.addSession(playlist, "MOVIE", "m1", "id:m1", now.toEpochMilliseconds(), 300)
        live.noteGroups(playlist, listOf("g1"))
        live.playedOk(playlist, "c1", 500)
        content.deleteSource(playlist)
        assertEquals(0L, live.sessionCount(playlist))
        assertTrue(live.playback(playlist).isEmpty())
        assertTrue(live.newGroups(playlist).isEmpty())
    }
}
