package app.iptvplayer.ingestion

import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.tmdb.TmdbClient
import app.iptvplayer.protocols.tmdb.tmdbPages
import app.iptvplayer.storage.LibraryStore
import app.iptvplayer.storage.ListEntry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * TMDB with the viewer's own key (ADR-0038): the key lives in the platform secret store and nowhere else, is checked with
 * TMDB before it is kept, and is used only to read public lists — trending, popular, top rated — at most once a day. Those
 * lists are stored and matched against each library locally; nothing about the library leaves the television.
 */
public class TmdbService(
    transport: HttpTransport,
    private val secrets: SecretStore,
    private val library: LibraryStore,
    private val clock: Clock,
    /** Where TMDB is; a function so debug builds can point it at the in-app test server for device tests. */
    base: () -> String = { TmdbClient.BASE_URL },
) {
    private val fetcher = HttpFetcher(transport)
    private val readBase = base
    private val client get() = TmdbClient(fetcher, readBase())

    public suspend fun hasKey(): Boolean = secrets.exists(KEY)

    /** Checks [raw] with TMDB and keeps it when accepted; returns why not otherwise. The key is never logged or returned. */
    public suspend fun setKey(raw: String): DomainError? {
        val trimmed = raw.trim()
        // Too short or long to be a key: say so the way TMDB would, without asking it.
        if (trimmed.length !in KEY_LENGTHS) return DomainError.Auth(AuthFailure.INVALID_CREDENTIALS)
        val key = Secret(trimmed)
        client.check(key)?.let { return it }
        secrets.put(KEY, SecretBundle(password = key))
        return null
    }

    /** Forgets the key and every list read with it. */
    public suspend fun removeKey() {
        secrets.delete(KEY)
        library.clearLists()
    }

    /**
     * Reads every list again when the stored ones are older than [maxAge] (or when [force]d). Stops at the first error that
     * concerns the key or TMDB as a whole, keeping the lists read before it. Returns that error, or null.
     */
    public suspend fun refresh(force: Boolean = false, maxAge: Duration = REFRESH_EVERY): DomainError? {
        val key = secrets.get(KEY)?.password ?: return null
        val fetched = library.listsFetchedAt()
        if (!force && fetched != null && clock.now() - fetched < maxAge) return null
        for (list in ExternalList.entries) {
            val entries = ArrayList<ListEntry>()
            for (page in 1..list.tmdbPages) {
                val (titles, error) = client.page(key, list, page)
                if (error != null) return error
                titles.forEach { t ->
                    val (tmdbKey, titleKey, bareKey) = t.workKeys
                    entries += ListEntry(t.id, t.type, t.title, t.year, t.rating, t.votes, tmdbKey, titleKey, bareKey)
                }
                if (titles.isEmpty()) break
            }
            library.saveList(list.name, entries.distinctBy { it.tmdbId })
        }
        return null
    }

    public companion object {
        private val KEY = CredentialRef("tmdb-api-key")

        /** TMDB v3 keys are 32 hexadecimal characters; a read access token is a long JWT. Anything else is a paste error. */
        private val KEY_LENGTHS = 16..512
        public val REFRESH_EVERY: Duration = 20.hours
    }
}
