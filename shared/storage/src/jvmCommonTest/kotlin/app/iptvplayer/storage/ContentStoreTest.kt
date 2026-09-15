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
import app.iptvplayer.domain.model.IdentityHints
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ContentStoreTest {
    private val file: File = File.createTempFile("content-store", ".db").also { it.delete() }
    private val driver = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    private var now = Instant.parse("2026-09-15T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private val store = ContentStore(driver, clock)
    private val playlist = PlaylistId("5b1c9d0e-7f3a-4e21-9c55-0a8b7d6e4f10")

    @AfterTest
    fun cleanUp() {
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    private fun addSource() = store.addSource(
        playlist,
        ProviderId("0f6e2c1a-3b4d-4c5e-8f90-a1b2c3d4e5f6"),
        "Example TV",
        PlaylistType.XTREAM,
        "http://panel.example.com:8080",
        UrlTemplate("http://panel.example.com:8080/player_api.php?username={credential:username}&password={credential:password}"),
        CredentialRef("cred-1"),
        TransportSecurity.CLEARTEXT,
    )

    private fun channel(id: String, name: String, groups: List<String>) = Channel(
        ChannelId(id), playlist, groups.map { GroupId(it) }, name, null, null, "$id.example", null, emptyList(), emptyList(), null,
        listOf(MediaSourceId("ms_$id")), null, emptyMap(), IdentityHints(null, null, name.lowercase()),
    )

    private fun xtreamSource(id: String) = MediaSource(
        MediaSourceId("ms_$id"),
        ContentRef(ContentType.CHANNEL, id),
        MediaLocator.XtreamStream(XtreamStreamKind.LIVE, id.removePrefix("ch_"), "ts"),
        StreamProtocol.PROGRESSIVE_TS,
        MediaHeaders(
            userAgent = "ExamplePlayer/1.0",
            custom = mapOf("X-Test" to "1"),
            sensitive = mapOf("Cookie" to CredentialRef("hdr-1")),
        ),
        null,
        null,
        0,
    )

    private fun import(vararg channels: Pair<String, List<String>>, groups: List<String> = listOf("grp_news", "grp_sports")) {
        now = now.plus(kotlin.time.Duration.parse("1m"))
        val writer = store.beginLiveSnapshot(playlist, batchSize = 2)
        groups.forEachIndexed { index, id ->
            writer.group(
                ChannelGroup(
                    GroupId(
                        id,
                    ),
                    playlist,
                    ContentKind.LIVE,
                    id.removePrefix("grp_").replaceFirstChar {
                        it.uppercase()
                    },
                    index,
                    null,
                ),
            )
        }
        for ((id, memberOf) in channels) {
            writer.channel(
                channel(id, "Channel ${id.removePrefix("ch_")}", memberOf),
                xtreamSource(id),
                UrlTemplate("https://img.example.com/$id.png"),
            )
        }
        writer.publish()
    }

    @Test
    fun importPublishQueryAndReplace() {
        addSource()
        assertEquals("Example TV", store.sources().single().name)
        assertTrue(store.channels(playlist).isEmpty(), "nothing is visible before the first publish")

        import("ch_1" to listOf("grp_news"), "ch_2" to listOf("grp_news", "grp_sports"), "ch_3" to listOf("grp_sports"))
        assertEquals(listOf("News" to 2L, "Sports" to 2L), store.groups(playlist).map { it.title to it.channelCount })
        assertEquals(listOf("Channel 1", "Channel 2"), store.channels(playlist, "grp_news").map { it.name })
        assertEquals(3, store.channelCount(playlist))
        assertEquals(ImportStatus.PUBLISHED, store.unitState(playlist, ImportUnit.LIVE)!!.status)

        val source = store.mediaSources(playlist, ChannelId("ch_2")).single()
        assertEquals(MediaLocator.XtreamStream(XtreamStreamKind.LIVE, "2", "ts"), source.locator)
        assertEquals(mapOf("X-Test" to "1"), source.headers.custom)
        assertEquals(CredentialRef("hdr-1"), source.headers.sensitive["Cookie"])

        store.setFavorite(ChannelId("ch_3"), true)
        import("ch_3" to listOf("grp_sports"), "ch_4" to listOf("grp_sports"), groups = listOf("grp_sports"))
        assertEquals(listOf("Channel 3", "Channel 4"), store.channels(playlist).map { it.name }, "the previous snapshot is gone")
        assertEquals(listOf(ChannelId("ch_3")), store.favoriteChannels(playlist).map { it.id }, "favorites survive re-import")
        assertTrue(store.channels(playlist).first { it.id == ChannelId("ch_3") }.isFavorite)
    }

    @Test
    fun discardedSnapshotLeavesThePublishedOneIntact() {
        addSource()
        import("ch_1" to listOf("grp_news"))
        val writer = store.beginLiveSnapshot(playlist, batchSize = 1)
        writer.channel(channel("ch_9", "Channel 9", listOf("grp_news")), xtreamSource("ch_9"), null)
        writer.discard()
        assertEquals(listOf("Channel 1"), store.channels(playlist).map { it.name })
        store.markUnit(playlist, ImportUnit.LIVE, ImportStatus.FAILED, "HTTP_503")
        assertEquals("HTTP_503", store.unitState(playlist, ImportUnit.LIVE)!!.errorCode)
        assertEquals(listOf("Channel 1"), store.channels(playlist).map { it.name }, "a failed refresh keeps showing the last good channels")
    }

    @Test
    fun deletingASourceRemovesItsContentButNotFavorites() {
        addSource()
        import("ch_1" to listOf("grp_news"))
        store.setFavorite(ChannelId("ch_1"), true)
        store.deleteSource(playlist)
        assertNull(store.source(playlist))
        assertTrue(store.channels(playlist).isEmpty())
        assertFalse(store.sources().any())
    }
}
