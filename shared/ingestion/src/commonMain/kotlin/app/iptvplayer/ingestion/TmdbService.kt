package app.iptvplayer.ingestion

import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.library.TitleCleaner
import app.iptvplayer.domain.model.ContentType
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
import app.iptvplayer.storage.Portrait
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
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

    /**
     * The title artwork and cast portraits of a film or show the viewer opened (ADR-0039): from what is stored when it was
     * asked about in the last [ART_KEEP], otherwise from TMDB — one search and one page. [providerTmdbId] is the provider's
     * TMDB id when it gives one, which saves the search. Stored by title and year, so a row that only has those (Home's
     * hero) finds it too. Null without a key; stored art when TMDB fails. [ask] false reads only what is stored.
     */
    public suspend fun artwork(type: ContentType, title: String, year: Int?, providerTmdbId: String?, ask: Boolean = true): TitleArt? {
        val known = providerTmdbId?.toLongOrNull()
        val work = TitleCleaner.workKey(title, year)
        val stored = library.artOf(type, work)
        if (!ask || (stored != null && clock.now() - stored.fetchedAt < ART_KEEP)) return stored?.toArt()
        val key = secrets.get(KEY)?.password ?: return stored?.toArt()
        val id = known ?: client.find(key, type, title, year).let { (id, error) ->
            if (error != null) return stored?.toArt()
            id
        }
        if (id == null) {
            library.saveArt(type, work, null, null, emptyList())
            return TitleArt(null, null)
        }
        val (art, error) = client.artwork(key, type, id)
        if (error != null) return stored?.toArt()
        library.saveArt(
            type,
            work,
            id,
            art?.logoPath,
            art?.credits.orEmpty().map { Portrait(it.name, it.id, it.profilePath) },
            art?.backdropPath,
        )
        return TitleArt(
            art?.logoPath?.let {
                TmdbClient.imageUrl(it, LOGO_SIZE)
            },
            art?.backdropPath?.let { TmdbClient.imageUrl(it, BACKDROP_SIZE) },
        )
    }

    /** Portraits of [names] learned from TMDB, as image addresses. Local only. */
    public fun portraits(names: Collection<String>): Map<String, String> =
        library.portraitsOf(names).mapValues { TmdbClient.imageUrl(it.value, PORTRAIT_SIZE) }

    /** A person's portrait and biography (ADR-0039), stored after the first time; null without a key or when TMDB fails. */
    public suspend fun person(name: String): PersonArt? {
        val stored = library.personOf(name)
        if (stored?.biography != null && clock.now() - stored.fetchedAt < ART_KEEP) return stored.toArt()
        val key = secrets.get(KEY)?.password ?: return stored?.toArt()
        val (person, error) = client.person(key, name)
        if (error != null) return stored?.toArt()
        // An empty biography marks a person already asked about, so a person TMDB has nothing on is not asked again.
        library.savePerson(name, person?.id, person?.profilePath ?: stored?.profilePath, person?.biography.orEmpty())
        return library.personOf(name)?.toArt()
    }

    private fun app.iptvplayer.storage.StoredArt.toArt() =
        TitleArt(logoPath?.let { TmdbClient.imageUrl(it, LOGO_SIZE) }, backdropPath?.let { TmdbClient.imageUrl(it, BACKDROP_SIZE) })

    private fun app.iptvplayer.storage.StoredPerson.toArt() =
        PersonArt(profilePath?.let { TmdbClient.imageUrl(it, PROFILE_SIZE) }, biography?.takeIf { it.isNotBlank() })

    public companion object {
        private val ART_KEEP = 30.days
        private const val LOGO_SIZE = "w500"
        private const val BACKDROP_SIZE = "w1280"
        private const val PORTRAIT_SIZE = "w185"
        private const val PROFILE_SIZE = "h632"
        private val KEY = CredentialRef("tmdb-api-key")

        /** TMDB v3 keys are 32 hexadecimal characters; a read access token is a long JWT. Anything else is a paste error. */
        private val KEY_LENGTHS = 16..512
        public val REFRESH_EVERY: Duration = 20.hours
    }
}

/** A title's artwork from TMDB: its title logo and a backdrop without words, when TMDB has them. */
public data class TitleArt(public val logoUrl: String?, public val backdropUrl: String? = null)

/** A person from TMDB: a portrait and a biography, when TMDB has them. */
public data class PersonArt(public val photoUrl: String?, public val biography: String?)
