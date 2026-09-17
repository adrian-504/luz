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
        private const val MAX_RATING = 10.0
        internal const val PARSE_NOT_JSON = "TMDB_NOT_JSON"
        private const val BYTE_MASK = 0xFF
        private const val ASCII = 128
        private const val HEX = 16
        private const val UNRESERVED = "-._~"
    }
}
