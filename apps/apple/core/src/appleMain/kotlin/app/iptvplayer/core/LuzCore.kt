package app.iptvplayer.core

import app.iptvplayer.domain.id.ChannelId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.PreferredStreamFormat
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.ingestion.AddSourceResult
import app.iptvplayer.ingestion.SourceService
import app.iptvplayer.platform.apple.AppleClock
import app.iptvplayer.platform.apple.ApplePlatformCapabilities
import app.iptvplayer.platform.apple.KeychainSecretStore
import app.iptvplayer.platform.apple.UrlSessionTransport
import app.iptvplayer.protocols.media.ResolveResult
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.EpgStore
import app.iptvplayer.storage.openIptvDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * The shared engine as the iPhone app sees it (Phase 10): add a provider, list its channels, and resolve one to a stream
 * right before playback. Everything behind it — importing, storage, the Keychain, URLSession — is the same code as on
 * Android. Suspending functions appear in Swift as `async`.
 *
 * Credentials never cross this API except inward: [addXtream] takes them and hands them to the Keychain; the database
 * keeps templates only. [stream] returns the one credential-bearing value there is, a playable URL, for the player alone:
 * Swift must not log it or store it.
 */
public class LuzCore internal constructor(
    databasePath: String,
    transport: HttpTransport,
    secrets: SecretStore,
) {
    public constructor(databasePath: String) : this(databasePath, UrlSessionTransport(), KeychainSecretStore())

    private val driver = openIptvDatabase(databasePath)
    private val content = ContentStore(driver, AppleClock)
    private val service = SourceService(
        transport,
        secrets,
        content,
        EpgStore(driver),
        AppleClock,
        ApplePlatformCapabilities,
    )

    /** Checks the login with the provider, keeps it in the Keychain and imports the live channels. */
    public suspend fun addXtream(name: String?, server: String, username: String, password: String): AddOutcome = io {
        when (val result = service.addXtream(name, server, username, password)) {
            is AddSourceResult.Added -> AddOutcome(added = true, reason = null, channels = result.live.itemCount)
            is AddSourceResult.Rejected -> AddOutcome(added = false, reason = result.reason.name, channels = 0)
        }
    }

    /** The source the app shows, when there is one. */
    public suspend fun source(): SourceItem? = io {
        content.sources().firstOrNull()?.let { SourceItem(it.playlistId.value, it.name, content.channelCount(it.playlistId).toInt()) }
    }

    /** The source's channels in the provider's order. */
    public suspend fun channels(sourceId: String): List<ChannelItem> = io {
        content.channels(PlaylistId(sourceId)).map { ChannelItem(it.id.value, it.name, it.number) }
    }

    /**
     * A channel's stream, resolved now. Live channels ask for HLS first so AVPlayer plays them; [Stream.engine] says which
     * player a stream needs (ADR-0041). Null when the channel is gone or its login is missing.
     */
    public suspend fun stream(sourceId: String, channelId: String): Stream? = io {
        val resolved = service.resolveChannel(PlaylistId(sourceId), ChannelId(channelId), PreferredStreamFormat.HLS)
        (resolved as? ResolveResult.Resolved)?.source?.let {
            Stream(it.url.unsafeRawValue(), if (it.protocol in AVPLAYER) Engine.AVPLAYER else Engine.VLC)
        }
    }

    /** Forgets every source and its login. */
    public suspend fun removeAll(): Unit = io {
        content.sources().forEach { service.deleteSource(it.playlistId) }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val AVPLAYER = setOf(StreamProtocol.HLS, StreamProtocol.PROGRESSIVE_MP4)
    }
}

public class AddOutcome(public val added: Boolean, public val reason: String?, public val channels: Int)

public class SourceItem(public val id: String, public val name: String, public val channels: Int)

public class ChannelItem(public val id: String, public val name: String, public val number: Int?)

/** A stream for the player only: [url] carries the login and is never logged or stored. */
public class Stream(public val url: String, public val engine: Engine) {
    override fun toString(): String = "Stream(engine=$engine)"
}

public enum class Engine { AVPLAYER, VLC }
