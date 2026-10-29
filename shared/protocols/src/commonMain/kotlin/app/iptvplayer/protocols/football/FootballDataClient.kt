package app.iptvplayer.protocols.football

import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
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
import kotlin.time.Instant

/** A competition football-data.org offers: its numeric id, the short code used in paths (PL, CL), its name and country or region. */
public data class FootballCompetition(
    public val id: Long,
    public val code: String,
    public val name: String,
    public val area: String?,
    public val emblem: String?,
)

/** A club or national side as football-data.org has it. */
public data class FootballTeam(
    public val id: Long,
    public val name: String,
    public val shortName: String?,
    public val tla: String?,
    public val crest: String?,
)

/** One fixture between two known teams. [status] is football-data.org's own word (SCHEDULED, TIMED, IN_PLAY, FINISHED...). */
public data class FootballMatch(
    public val id: Long,
    public val competitionCode: String,
    public val competitionName: String,
    public val home: FootballTeam,
    public val away: FootballTeam,
    public val kickoffUtc: Instant,
    public val status: String,
)

/** One row of a league table (the TOTAL table: home and away games together). */
public data class FootballStanding(
    public val position: Int,
    public val team: FootballTeam,
    public val played: Int,
    public val points: Int,
    public val goalDiff: Int,
)

/**
 * football-data.org's v4 API, for the viewer who gives Luz their own token. The token travels only as the `X-Auth-Token`
 * request header, a sensitive one the transport never logs: never in the URL, and never in an error. A response is read
 * through the same content checks and size limits as TMDB's.
 *
 * A refused token (400, 401) is a [DomainError.Auth]; something outside the viewer's plan (403) is [RESTRICTED]. Which
 * status a refused token gets is NOT YET VERIFIED against the live service, so a token check treats any error as refusal.
 * The free plan allows ten requests a minute; when football-data.org says so (429) the error is [RATE_LIMITED], a
 * [DomainError.Http] that is already retryable, so a caller can tell "wait and try again" from every other failure.
 */
public class FootballDataClient(
    private val fetcher: HttpFetcher,
    private val base: String = BASE_URL,
    private val limits: ParseLimits = ParseLimits.XTREAM_JSON,
) {
    /** Null when [token] is accepted; an [DomainError.Auth] when football-data.org refuses it; another error when it is unreachable. */
    public suspend fun check(token: Secret<String>): DomainError? = when (val body = fetch(token, "competitions", emptyList())) {
        is Body.Document -> null
        is Body.Error -> body.error
    }

    /** The competitions the viewer's plan can read. */
    public suspend fun competitions(token: Secret<String>): Pair<List<FootballCompetition>, DomainError?> =
        when (val body = fetch(token, "competitions", emptyList())) {
            is Body.Error -> emptyList<FootballCompetition>() to body.error
            is Body.Document -> {
                val list = LenientObject.of(body.element) {}?.array("competitions").orEmpty().mapNotNull { entry ->
                    val obj = LenientObject.of(entry) {} ?: return@mapNotNull null
                    val id = obj.long("id") ?: return@mapNotNull null
                    val code = obj.string("code") ?: return@mapNotNull null
                    val name = obj.string("name") ?: return@mapNotNull null
                    FootballCompetition(id, code, name, obj.obj("area")?.string("name"), obj.string("emblem"))
                }
                list to null
            }
        }

    /** The teams of one competition, by its [code]. */
    public suspend fun competitionTeams(token: Secret<String>, code: String): Pair<List<FootballTeam>, DomainError?> =
        when (val body = fetch(token, "competitions/${encode(code)}/teams", emptyList())) {
            is Body.Error -> emptyList<FootballTeam>() to body.error
            is Body.Document -> LenientObject.of(body.element) {}?.array("teams").orEmpty().mapNotNull { team(it) } to null
        }

    /** The matches of one competition from [from] to [to] (both `YYYY-MM-DD`, UTC dates). */
    public suspend fun matches(token: Secret<String>, code: String, from: String, to: String): Pair<List<FootballMatch>, DomainError?> =
        matchesAt(token, "competitions/${encode(code)}/matches", from, to)

    /** The matches of one team, in any competition, from [from] to [to] (both `YYYY-MM-DD`, UTC dates). */
    public suspend fun teamMatches(token: Secret<String>, teamId: Long, from: String, to: String): Pair<List<FootballMatch>, DomainError?> =
        matchesAt(token, "teams/$teamId/matches", from, to)

    /** The league table of one competition: the TOTAL table only (the first, when a cup sends one per group). */
    public suspend fun standings(token: Secret<String>, code: String): Pair<List<FootballStanding>, DomainError?> =
        when (val body = fetch(token, "competitions/${encode(code)}/standings", emptyList())) {
            is Body.Error -> emptyList<FootballStanding>() to body.error
            is Body.Document -> {
                val total = LenientObject.of(body.element) {}?.array("standings").orEmpty().mapNotNull { LenientObject.of(it) {} }
                    .firstOrNull { it.string("type") == TOTAL }
                val rows = total?.array("table").orEmpty().mapNotNull { entry ->
                    val obj = LenientObject.of(entry) {} ?: return@mapNotNull null
                    val team = obj.obj("team")?.let { team(it) } ?: return@mapNotNull null
                    FootballStanding(
                        obj.int("position") ?: return@mapNotNull null,
                        team,
                        obj.int("playedGames") ?: 0,
                        obj.int("points") ?: 0,
                        obj.int("goalDifference") ?: 0,
                    )
                }
                rows to null
            }
        }

    private suspend fun matchesAt(token: Secret<String>, path: String, from: String, to: String): Pair<List<FootballMatch>, DomainError?> {
        if (!isDate(from) || !isDate(to)) return emptyList<FootballMatch>() to DomainError.Parse(PARSE_BAD_DATE)
        return when (val body = fetch(token, path, listOf("dateFrom" to from, "dateTo" to to))) {
            is Body.Error -> emptyList<FootballMatch>() to body.error
            is Body.Document -> LenientObject.of(body.element) {}?.array("matches").orEmpty().mapNotNull { match(it) } to null
        }
    }

    /** A match between two known teams; a knockout slot still to be decided has no team id, and is left out. */
    private fun match(element: JsonElement): FootballMatch? {
        val obj = LenientObject.of(element) {} ?: return null
        val id = obj.long("id") ?: return null
        val competition = obj.obj("competition") ?: return null
        val code = competition.string("code") ?: return null
        val home = obj.obj("homeTeam")?.let { team(it) } ?: return null
        val away = obj.obj("awayTeam")?.let { team(it) } ?: return null
        val kickoff = obj.string("utcDate")?.let { instant(it) } ?: return null
        return FootballMatch(id, code, competition.string("name") ?: code, home, away, kickoff, obj.string("status") ?: UNKNOWN_STATUS)
    }

    private fun team(element: JsonElement): FootballTeam? = LenientObject.of(element) {}?.let { team(it) }

    private fun team(obj: LenientObject): FootballTeam? {
        val id = obj.long("id") ?: return null
        val name = obj.string("name") ?: return null
        return FootballTeam(id, name, obj.string("shortName"), obj.string("tla"), obj.string("crest"))
    }

    private fun instant(text: String): Instant? = try {
        Instant.parse(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Only the shape `YYYY-MM-DD`: the API itself refuses a date that is not on the calendar. */
    private fun isDate(text: String): Boolean =
        text.length == DATE_LENGTH && text.indices.all { if (it == YEAR_END || it == MONTH_END) text[it] == '-' else text[it] in '0'..'9' }

    private sealed interface Body {
        class Document(val element: JsonElement) : Body

        class Error(val error: DomainError) : Body
    }

    private suspend fun fetch(token: Secret<String>, path: String, parameters: List<Pair<String, String>>): Body {
        val query = parameters.joinToString("&") { (name, value) -> "$name=${encode(value)}" }
        val url = SensitiveUrl.of(if (query.isEmpty()) "$base/$path" else "$base/$path?$query")
        val fetched = fetcher.get(url, RequestClass.METADATA, UrlContext.API, sensitiveHeaders = mapOf(TOKEN_HEADER to token))
        val response = when (fetched) {
            is FetchResult.Failure -> return Body.Error(
                when (fetched.status) {
                    400, 401 -> DomainError.Auth(AuthFailure.INVALID_CREDENTIALS)
                    403 -> RESTRICTED
                    429 -> RATE_LIMITED
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

    /** Percent-encodes everything but unreserved characters, so a value cannot break the URL. */
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
        public const val BASE_URL: String = "https://api.football-data.org/v4"

        /** What a call returns when football-data.org answers 429: too many requests for the viewer's plan. */
        public val RATE_LIMITED: DomainError = DomainError.Http(429)

        /** What a call returns for something outside the viewer's plan (403): a competition the free plan does not cover. */
        public val RESTRICTED: DomainError = DomainError.Http(403)
        internal const val PARSE_NOT_JSON = "FOOTBALL_NOT_JSON"
        internal const val PARSE_BAD_DATE = "FOOTBALL_BAD_DATE"
        internal const val TOKEN_HEADER = "X-Auth-Token"
        private const val TOTAL = "TOTAL"
        private const val UNKNOWN_STATUS = "UNKNOWN"
        private const val DOCUMENT_LIMIT = 4 * 1024 * 1024
        private const val DATE_LENGTH = 10
        private const val YEAR_END = 4
        private const val MONTH_END = 7
        private const val BYTE_MASK = 0xFF
        private const val ASCII = 128
        private const val HEX = 16
        private const val UNRESERVED = "-._~"
    }
}
