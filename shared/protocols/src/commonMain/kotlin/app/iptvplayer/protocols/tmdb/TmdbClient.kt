package app.iptvplayer.protocols.tmdb

import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.library.TitleCleaner
import app.iptvplayer.domain.model.ContentType
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.ports.ParseLimits
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.protocols.io.PrefixReplaySource
import app.iptvplayer.protocols.io.readPrefix
import app.iptvplayer.protocols.json.JsonArrayStreamer
import app.iptvplayer.protocols.json.LenientObject
import app.iptvplayer.protocols.net.FetchResult
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.net.RequestClass
import app.iptvplayer.protocols.sniff.ContentSniffer
import app.iptvplayer.protocols.sniff.SniffedFormat
import kotlinx.serialization.json.JsonElement

/** Where each list is on TMDB and how many pages of twenty are read (ADR-0038). None is about the viewer or their library. */
public val ExternalList.tmdbPath: String
    get() = when (this) {
        ExternalList.TRENDING_MOVIES -> "trending/movie/week"
        ExternalList.TRENDING_SERIES -> "trending/tv/week"
        ExternalList.POPULAR_MOVIES -> "movie/popular"
        ExternalList.POPULAR_SERIES -> "tv/popular"
        ExternalList.TOP_MOVIES -> "movie/top_rated"
        ExternalList.TOP_SERIES -> "tv/top_rated"
    }

public val ExternalList.tmdbPages: Int
    get() = when (this) {
        ExternalList.TRENDING_MOVIES, ExternalList.TRENDING_SERIES -> 2
        ExternalList.POPULAR_MOVIES, ExternalList.POPULAR_SERIES -> 5
        ExternalList.TOP_MOVIES, ExternalList.TOP_SERIES -> 10
    }

/** One entry of a TMDB list: enough to find the title in a library and to rank it. */
public data class TmdbTitle(
    public val id: Long,
    public val type: ContentType,
    public val title: String,
    public val year: Int?,
    public val rating: Double?,
    public val votes: Int?,
) {
    /**
     * The keys a library row of the same work may carry: TMDB's own id when the provider sent it; the title with its year;
     * and the title alone, for a provider that gave no year.
     */
    public val workKeys: List<String> get() = listOf("tmdb:$id", TitleCleaner.workKey(title, year), TitleCleaner.workKey(title, null))
}

/** A person credited on a title, with TMDB's id for them and their portrait's path when it has one. */
public data class TmdbCredit(public val id: Long, public val name: String, public val profilePath: String?, public val director: Boolean)

/** What TMDB adds to one title's page: its stylised title artwork and the portraits of the people in it. */
public data class TmdbArtwork(
    public val id: Long,
    public val logoPath: String?,
    public val credits: List<TmdbCredit>,
    /** A wide picture without words (the title is drawn over it), or TMDB's main one. */
    public val backdropPath: String? = null,
)

/** One episode as TMDB has it: what it is called, a picture from it, and what it is about. */
public data class TmdbEpisode(
    public val number: Int,
    public val name: String?,
    public val stillPath: String?,
    public val overview: String?,
)

/** A person's own page on TMDB. */
public data class TmdbPerson(public val id: Long, public val name: String, public val profilePath: String?, public val biography: String?)

/**
 * The Movie Database's v3 API, for the viewer who gives Luz their own key (ADR-0038). Only public lists are read: what is
 * trending this week, popular and top rated. The key travels as the `api_key` query parameter of a [SensitiveUrl], so it
 * is never printed, and a response is read through the same content checks and size limits as a provider's JSON.
 */
public class TmdbClient(
    private val fetcher: HttpFetcher,
    private val base: String = BASE_URL,
    private val limits: ParseLimits = ParseLimits.XTREAM_JSON,
) {
    /** Null when [key] is accepted; an [DomainError.Auth] when TMDB refuses it; another error when TMDB could not be reached. */
    public suspend fun check(key: Secret<String>): DomainError? = when (val body = fetch(key, "configuration", emptyList())) {
        is Body.Document -> null
        is Body.Error -> body.error
    }

    /** One page (1-based) of [list], or the error that stopped it. */
    public suspend fun page(key: Secret<String>, list: ExternalList, page: Int): Pair<List<TmdbTitle>, DomainError?> =
        when (val body = fetch(key, list.tmdbPath, listOf("page" to page.toString()))) {
            is Body.Error -> emptyList<TmdbTitle>() to body.error
            is Body.Document -> {
                val results = LenientObject.of(body.element) {}?.array("results")
                results.orEmpty().mapNotNull { title(it, list.type) } to null
            }
        }

    /**
     * TMDB's id for a title found by name (and year when known), or null when TMDB has none. Only the name and year are sent
     * — the question anyone searching TMDB's site asks (ADR-0039).
     */
    public suspend fun find(key: Secret<String>, type: ContentType, title: String, year: Int?): Pair<Long?, DomainError?> {
        val path = if (type == ContentType.SERIES) "search/tv" else "search/movie"
        val yearName = if (type == ContentType.SERIES) "first_air_date_year" else "year"
        val parameters = listOf("query" to title) + listOfNotNull(year?.let { yearName to it.toString() })
        return when (val body = fetch(key, path, parameters)) {
            is Body.Error -> null to body.error
            is Body.Document -> LenientObject.of(body.element) {}?.array("results")?.firstOrNull()
                ?.let { LenientObject.of(it) {}?.long("id") } to null
        }
    }

    /** A title's logo (English, or artwork without words), a backdrop and its credited people, in one request. */
    public suspend fun artwork(key: Secret<String>, type: ContentType, id: Long): Pair<TmdbArtwork?, DomainError?> {
        val path = (if (type == ContentType.SERIES) "tv/" else "movie/") + id
        val parameters = listOf("append_to_response" to "images,credits", "include_image_language" to "en,null")
        return when (val body = fetch(key, path, parameters)) {
            is Body.Error -> null to body.error
            is Body.Document -> {
                val root = LenientObject.of(body.element) {} ?: return null to null
                // TMDB holds many pictures for a title, each with the votes of the people who uploaded and rated them.
                // Logos: English first, then artwork with no words in it, best rated of those.
                val logos = root.obj("images")?.array("logos").orEmpty().mapNotNull { LenientObject.of(it) {} }
                val logo = (logos.filter { it.string("iso_639_1") == "en" } + logos.filter { it.string("iso_639_1") == null })
                    .maxByOrNull { rank(it) }?.string("file_path")
                // Backdrops: without words (no language) so the title can be drawn over them, wide enough for the screen,
                // and the best rated of those; TMDB's own main picture only if there is nothing else.
                val backdrops = root.obj("images")?.array("backdrops").orEmpty().mapNotNull { LenientObject.of(it) {} }
                val backdrop = backdrops.filter { it.string("iso_639_1") == null && (it.int("width") ?: 0) >= MIN_BACKDROP_WIDTH }
                    .maxByOrNull { rank(it) }?.string("file_path")
                    ?: backdrops.filter { it.string("iso_639_1") == null }.maxByOrNull { rank(it) }?.string("file_path")
                    ?: root.string("backdrop_path")
                val credits = root.obj("credits")
                fun people(field: String, director: Boolean) = credits?.array(field).orEmpty().mapNotNull { entry ->
                    val person = LenientObject.of(entry) {} ?: return@mapNotNull null
                    if (director && person.string("job") != "Director") return@mapNotNull null
                    val name = person.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    TmdbCredit(person.long("id") ?: return@mapNotNull null, name, person.string("profile_path"), director)
                }
                TmdbArtwork(id, logo, people("crew", true) + people("cast", false).take(MAX_CAST), backdrop) to null
            }
        }
    }

    /** One season of a show: each episode's name, still and description, in one request. */
    public suspend fun episodes(key: Secret<String>, showId: Long, season: Int): Pair<List<TmdbEpisode>, DomainError?> =
        when (val body = fetch(key, "tv/$showId/season/$season", emptyList())) {
            is Body.Error -> emptyList<TmdbEpisode>() to body.error
            is Body.Document -> {
                val episodes = LenientObject.of(body.element) {}?.array("episodes").orEmpty().mapNotNull { entry ->
                    val obj = LenientObject.of(entry) {} ?: return@mapNotNull null
                    val number = obj.int("episode_number") ?: return@mapNotNull null
                    TmdbEpisode(
                        number,
                        obj.string("name")?.trim()?.takeIf { it.isNotEmpty() },
                        obj.string("still_path"),
                        obj.string("overview")?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
                episodes to null
            }
        }

    /** A person's page found by name: the first match, with their portrait and biography. */
    public suspend fun person(key: Secret<String>, name: String): Pair<TmdbPerson?, DomainError?> {
        val found = when (val body = fetch(key, "search/person", listOf("query" to name))) {
            is Body.Error -> return null to body.error
            is Body.Document -> LenientObject.of(body.element) {}?.array("results")?.firstOrNull()?.let { LenientObject.of(it) {} }
        } ?: return null to null
        val id = found.long("id") ?: return null to null
        val biography = when (val page = fetch(key, "person/$id", emptyList())) {
            is Body.Error -> null
            is Body.Document -> LenientObject.of(page.element) {}?.string("biography")?.takeIf { it.isNotBlank() }
        }
        return TmdbPerson(id, found.string("name") ?: name, found.string("profile_path"), biography) to null
    }

    /**
     * How good a picture is held to be: its average vote, with a picture nobody has rated behind any that has been, and
     * the wider of two equals in front.
     */
    private fun rank(image: LenientObject): Double {
        val votes = image.int("vote_count") ?: 0
        val average = image.double("vote_average") ?: 0.0
        val width = (image.int("width") ?: 0) / WIDTH_WEIGHT
        return if (votes > 0) average * VOTE_WEIGHT + width else width
    }

    private fun title(element: JsonElement, type: ContentType): TmdbTitle? {
        val obj = LenientObject.of(element) {} ?: return null
        val id = obj.long("id") ?: return null
        val name = (obj.string("title") ?: obj.string("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val date = obj.string("release_date") ?: obj.string("first_air_date")
        val year = date?.take(YEAR_DIGITS)?.toIntOrNull()
        val rating = obj.double("vote_average")?.takeIf { it > 0 && it <= MAX_RATING }
        return TmdbTitle(id, type, name, year, rating, obj.int("vote_count"))
    }

    private sealed interface Body {
        class Document(val element: JsonElement) : Body

        class Error(val error: DomainError) : Body
    }

    private suspend fun fetch(key: Secret<String>, path: String, parameters: List<Pair<String, String>>): Body {
        val query = (listOf("api_key" to key.unsafeValue()) + parameters).joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        val url = SensitiveUrl.of("$base/$path?$query")
        val response = when (val fetched = fetcher.get(url, RequestClass.METADATA, UrlContext.API)) {
            is FetchResult.Failure -> return Body.Error(
                when (fetched.status) {
                    401, 403 -> DomainError.Auth(AuthFailure.INVALID_CREDENTIALS)
                    else -> fetched.error
                },
            )
            is FetchResult.Success -> fetched.response
        }
        try {
            val prefix = readPrefix(response.body, ContentSniffer.SNIFF_BYTES)
            if (ContentSniffer.sniff(prefix) != SniffedFormat.JSON) {
                return Body.Error(DomainError.Parse(PARSE_NOT_JSON))
            }
            val streamer = JsonArrayStreamer(limits.maxBytes, DOCUMENT_LIMIT, limits.maxDepth, limits.maxRecords)
            val element = streamer.parseDocument(PrefixReplaySource(prefix, response.body))
                ?: return Body.Error(DomainError.Parse(PARSE_NOT_JSON))
            return Body.Document(element)
        } finally {
            response.body.close()
        }
    }

    /** Percent-encodes everything but unreserved characters, so a key pasted with stray spaces cannot break the URL. */
    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and BYTE_MASK
            val char = code.toChar()
            if (code < ASCII && (char.isLetterOrDigit() || char in UNRESERVED)) {
                append(char)
            } else {
                append('%').append(code.toString(HEX).uppercase().padStart(2, '0'))
            }
        }
    }

    public companion object {
        public const val BASE_URL: String = "https://api.themoviedb.org/3"
        private const val DOCUMENT_LIMIT = 4 * 1024 * 1024
        private const val YEAR_DIGITS = 4
        private const val MAX_CAST = 20

        /** Where TMDB serves its images; [size] is one of its fixed widths (w185, w500, original). */
        private const val MIN_BACKDROP_WIDTH = 1280
        private const val VOTE_WEIGHT = 1000.0
        private const val WIDTH_WEIGHT = 10000.0

        public fun imageUrl(path: String, size: String): String = "https://image.tmdb.org/t/p/$size$path"
        private const val MAX_RATING = 10.0
        internal const val PARSE_NOT_JSON = "TMDB_NOT_JSON"
        private const val BYTE_MASK = 0xFF
        private const val ASCII = 128
        private const val HEX = 16
        private const val UNRESERVED = "-._~"
    }
}
