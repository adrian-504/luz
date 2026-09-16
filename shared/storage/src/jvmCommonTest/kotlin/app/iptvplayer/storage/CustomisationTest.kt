package app.iptvplayer.storage

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.id.GroupId
import app.iptvplayer.domain.id.MediaSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.id.ProviderId
import app.iptvplayer.domain.model.Channel
import app.iptvplayer.domain.model.ChannelGroup
import app.iptvplayer.domain.model.ContentKind
import app.iptvplayer.domain.model.ContentRef
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.model.CustomisationTarget
import app.iptvplayer.domain.model.IdentityHints
import app.iptvplayer.domain.model.MediaHeaders
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.MediaSource
import app.iptvplayer.domain.model.PlaylistType
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.TransportSecurity
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.storage.db.IptvDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Hiding and renaming (FR-PLM-001): the viewer's choices apply to what they see, survive a refresh that replaces every
 * imported row, and never change what the provider sent.
 */
class CustomisationTest {
    private val file: File = File.createTempFile("customisation", ".db").also { it.delete() }
    private val driver = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    private val clock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-16T12:00:00Z")

        override fun monotonicNanos(): Long = 0
    }
    private val content = ContentStore(driver, clock)
    private val playlist = PlaylistId("pl")

    @AfterTest
    fun cleanUp() {
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun import() {
        val writer = content.beginLiveSnapshot(playlist, batchSize = 100)
        writer.group(ChannelGroup(GroupId("news"), playlist, ContentKind.LIVE, "News", 0, null))
        writer.group(ChannelGroup(GroupId("sport"), playlist, ContentKind.LIVE, "Sport", 1, null))
        listOf("one" to "news", "two" to "news", "three" to "sport").forEach { (id, group) ->
            writer.channel(
                Channel(
                    ChannelId(id), playlist, listOf(GroupId(group)), "Channel $id", null, null, null, null,
                    emptyList(), emptyList(), null, listOf(MediaSourceId("ms_$id")), null, emptyMap(),
                    IdentityHints(null, null, id),
                ),
                MediaSource(
                    MediaSourceId("ms_$id"),
                    ContentRef(ContentType.CHANNEL, id),
                    MediaLocator.XtreamStream(XtreamStreamKind.LIVE, "1", "ts"),
                    StreamProtocol.PROGRESSIVE_TS,
                    MediaHeaders(),
                    null,
                    null,
                    0,
                ),
                null,
            )
        }
        writer.publish()
    }

    private fun addSource() = content.addSource(
        playlist,
        ProviderId("prov"),
        "Provider",
        PlaylistType.M3U_URL,
        "http://example.com",
        UrlTemplate("http://example.com/list.m3u"),
        CredentialRef("cred"),
        TransportSecurity.CLEARTEXT,
    )

    @Test
    fun hiddenChannelsAndCategoriesDisappearFromEveryListAndComeBack() {
        addSource()
        import()
        assertEquals(listOf("one", "two", "three"), content.channels(playlist).map { it.id.value })

        content.hide(playlist, CustomisationTarget.CHANNEL, "two")
        assertEquals(listOf("one", "three"), content.channels(playlist).map { it.id.value }, "hidden everywhere")
        assertEquals(listOf("one"), content.channels(playlist, "news").map { it.id.value }, "and inside its category")
        assertEquals(1L, content.groups(playlist).first { it.id == "news" }.channelCount, "the category counts what is left")
        assertTrue(content.searchChannels(playlist, "Channel two", 10).isEmpty(), "and search does not offer it")

        content.hide(playlist, CustomisationTarget.CHANNEL_GROUP, "sport")
        assertEquals(listOf("news"), content.groups(playlist).map { it.id })
        assertEquals(
            listOf("one", "three"),
            content.channels(playlist).map { it.id.value },
            "hiding a category hides the category, not the channels in it",
        )

        assertEquals(listOf("two"), content.hidden(playlist, CustomisationTarget.CHANNEL))
        assertEquals(2L, content.hiddenCount(playlist))

        content.unhide(playlist, CustomisationTarget.CHANNEL, "two")
        content.unhide(playlist, CustomisationTarget.CHANNEL_GROUP, "sport")
        assertEquals(listOf("one", "two", "three"), content.channels(playlist).map { it.id.value })
        assertEquals(listOf("news", "sport"), content.groups(playlist).map { it.id })
    }

    @Test
    fun renamingACategoryShowsTheViewersNameAndClearingItRestoresTheProvidersOwn() {
        addSource()
        import()
        content.setLabel(playlist, CustomisationTarget.CHANNEL_GROUP, "news", "  Headlines  ")
        assertEquals(listOf("Headlines", "Sport"), content.groups(playlist).map { it.title }, "trimmed, and only that one")
        assertEquals(mapOf("news" to "Headlines"), content.labels(playlist, CustomisationTarget.CHANNEL_GROUP))

        content.setLabel(playlist, CustomisationTarget.CHANNEL_GROUP, "news", "   ")
        assertEquals(listOf("News", "Sport"), content.groups(playlist).map { it.title }, "a blank name restores the provider's")
    }

    @Test
    fun choicesSurviveARefreshAndLeaveWithTheSource() {
        addSource()
        import()
        content.hide(playlist, CustomisationTarget.CHANNEL, "two")
        content.setLabel(playlist, CustomisationTarget.CHANNEL_GROUP, "news", "Headlines")

        import() // a refresh: every imported row is replaced
        assertEquals(listOf("one", "three"), content.channels(playlist).map { it.id.value }, "still hidden after a refresh")
        assertEquals("Headlines", content.groups(playlist).first { it.id == "news" }.title, "still renamed after a refresh")

        content.deleteSource(playlist)
        addSource()
        import()
        assertEquals(listOf("one", "two", "three"), content.channels(playlist).map { it.id.value }, "removing the source forgets them")
        assertEquals("News", content.groups(playlist).first { it.id == "news" }.title)
    }
}
