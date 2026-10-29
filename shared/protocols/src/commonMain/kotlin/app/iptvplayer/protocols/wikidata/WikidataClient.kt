package app.iptvplayer.protocols.wikidata

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.net.UrlContext
import app.iptvplayer.domain.ports.ParseLimits
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

/** One way a team is written: [text] in [language] (ar, fr or en), and where it came from. */
public data class TeamName(public val text: String, public val language: String, public val origin: String = "wikidata")

/**
 * Wikidata's public API, to learn the names a football team goes by in Arabic, French and English, so a channel called
 * after the team in one language can be matched with a fixture written in another. No key is needed; Wikidata asks every
 * client to say who it is, so each request carries a User-Agent. Only the team's name is sent.
 *
 * Label coverage for smaller Arab clubs is unverified: a team may come back with an English name only, or with none.
 */
public class WikidataClient(
    private val fetcher: HttpFetcher,
    private val base: String = BASE_URL,
    private val limits: ParseLimits = ParseLimits.XTREAM_JSON,
) {
    /**
     * The labels and aliases of the football club Wikidata finds for [teamName]. The first search hit whose description
     * mentions football, soccer or a club is taken; with no such hit the list is empty, with no error and no second request.
     */
    public suspend fun names(teamName: String): Pair<List<TeamName>, DomainError?> {
        val searchParameters = listOf(
            "action" to "wbsearchentities",
            "search" to teamName,
            "language" to "en",
            "type" to "item",
            "format" to "json",
            "limit" to "5",
        )
        val search = when (val body = fetch(searchParameters)) {
            is Body.Error -> return emptyList<TeamName>() to body.error
            is Body.Document -> body.element
        }
        val hit = LenientObject.of(search) {}?.array("search").orEmpty().mapNotNull { LenientObject.of(it) {} }.firstOrNull { entry ->
            val description = entry.string("description")?.lowercase().orEmpty()
            entry.string("id") != null && FOOTBALL_WORDS.any { it in description }
        }
        val id = hit?.string("id") ?: return emptyList<TeamName>() to null
        val entityParameters = listOf(
            "action" to "wbgetentities",
            "ids" to id,
            "props" to "labels|aliases",
            "languages" to LANGUAGES.joinToString("|"),
            "format" to "json",
        )
        val entities = when (val body = fetch(entityParameters)) {
            is Body.Error -> return emptyList<TeamName>() to body.error
            is Body.Document -> body.element
        }
        val entity = LenientObject.of(entities) {}?.obj("entities")?.obj(id) ?: return emptyList<TeamName>() to null
        val names = buildList {
            for (language in LANGUAGES) {
                entity.obj("labels")?.obj(language)?.string("value")?.let { add(TeamName(it, language)) }
                entity.obj("aliases")?.array(language).orEmpty().forEach { alias ->
                    LenientObject.of(alias) {}?.string("value")?.let { add(TeamName(it, language)) }
                }
            }
        }
        return names.filter { it.text.isNotBlank() }.distinct() to null
    }

    private sealed interface Body {
        class Document(val element: JsonElement) : Body

        class Error(val error: DomainError) : Body
    }

    private suspend fun fetch(parameters: List<Pair<String, String>>): Body {
        val query = parameters.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        val url = SensitiveUrl.of("$base?$query")
        val response = when (val fetched = fetcher.get(url, RequestClass.METADATA, UrlContext.API, mapOf("User-Agent" to USER_AGENT))) {
            is FetchResult.Failure -> return Body.Error(fetched.error)
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

    /** Percent-encodes everything but unreserved characters, so a team name with spaces or Arabic letters cannot break the URL. */
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
        public const val BASE_URL: String = "https://www.wikidata.org/w/api.php"
        public const val USER_AGENT: String = "Luz/1.0 (https://github.com/adrian-504/luz)"
        internal const val PARSE_NOT_JSON = "WIKIDATA_NOT_JSON"
        private val LANGUAGES = listOf("ar", "fr", "en")
        private val FOOTBALL_WORDS = listOf("football", "soccer", "club")
        private const val DOCUMENT_LIMIT = 4 * 1024 * 1024
        private const val BYTE_MASK = 0xFF
        private const val ASCII = 128
        private const val HEX = 16
        private const val UNRESERVED = "-._~"
    }
}
