package app.iptvplayer.ingestion

import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.protocols.football.FootballCompetition
import app.iptvplayer.protocols.football.FootballDataClient
import app.iptvplayer.protocols.football.FootballMatch
import app.iptvplayer.protocols.football.FootballTeam
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.wikidata.WikidataClient
import app.iptvplayer.storage.Fixture
import app.iptvplayer.storage.FollowKind
import app.iptvplayer.storage.SportCompetition
import app.iptvplayer.storage.SportStore
import app.iptvplayer.storage.SportTeam
import app.iptvplayer.storage.StandingRow
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Sport fixtures from football-data.org with the viewer's own token, team names from Wikidata (ADR-0057). The token lives
 * in the platform secret store and nowhere else, is checked before it is kept, and reads only what the viewer follows: the
 * next two weeks' matches of their teams and competitions, and their competitions' tables. Read at most once in each
 * six-hour slot of the viewer's day (from midnight, 06:00, noon and 18:00), one request every [spacing] so the free plan's
 * ten a minute is never reached, and never while something plays. Luz makes no channel claims: nothing here names one.
 */
public class SportService(
    transport: HttpTransport,
    private val secrets: SecretStore,
    private val sport: SportStore,
    private val clock: Clock,
    /** The viewer's offset from UTC at an instant, for the six-hour slots of their day. */
    private val utcOffset: (Instant) -> Duration = { Duration.ZERO },
    /** True while something plays: a refresh stops before its next request. */
    private val playing: () -> Boolean = { false },
    private val spacing: Duration = REQUEST_SPACING,
    private val pause: suspend (Duration) -> Unit = { delay(it) },
    /** Where football-data.org and Wikidata are; functions so debug builds can point them at the in-app test server. */
    footballBase: () -> String = { FootballDataClient.BASE_URL },
    wikidataBase: () -> String = { WikidataClient.BASE_URL },
) {
    private val fetcher = HttpFetcher(transport)
    private val readFootball = footballBase
    private val readWikidata = wikidataBase
    private val football get() = FootballDataClient(fetcher, readFootball())
    private val wikidata get() = WikidataClient(fetcher, readWikidata())
    private var lastRequest: Instant? = null

    public suspend fun hasToken(): Boolean = secrets.exists(TOKEN)

    /** Checks [raw] with football-data.org and keeps it when accepted; returns why not otherwise. Never logged or returned. */
    public suspend fun setToken(raw: String): DomainError? {
        val trimmed = raw.trim()
        if (trimmed.length !in TOKEN_LENGTHS || trimmed.any { it.isWhitespace() }) {
            return DomainError.Auth(AuthFailure.INVALID_CREDENTIALS)
        }
        val token = Secret(trimmed)
        // Any refusal of the very first request is the token's: a rate limit is the only other answer worth passing on.
        spaced { football.check(token) }?.let {
            return if (it == FootballDataClient.RATE_LIMITED) it else DomainError.Auth(AuthFailure.INVALID_CREDENTIALS)
        }
        secrets.put(TOKEN, SecretBundle(password = token))
        return null
    }

    /** Forgets the token and everything read with it; what the viewer follows stays, for a token given again. */
    public suspend fun removeToken() {
        secrets.delete(TOKEN)
        sport.clearFetched()
    }

    /** The competitions the token can read, for choosing what to follow; empty without a token. */
    public suspend fun competitions(): Pair<List<FootballCompetition>, DomainError?> {
        val token = token() ?: return emptyList<FootballCompetition>() to null
        return spaced { football.competitions(token) }
    }

    /** A competition's teams, for choosing one to follow; kept, so their names are searchable. Empty without a token. */
    public suspend fun teams(competition: String): Pair<List<SportTeam>, DomainError?> {
        val token = token() ?: return emptyList<SportTeam>() to null
        val (teams, error) = spaced { football.competitionTeams(token, competition) }
        if (error != null) return emptyList<SportTeam>() to error
        val kept = teams.map { it.toTeam() }
        kept.forEach { sport.saveTeam(it) }
        return kept to null
    }

    /**
     * Reads what can be followed (the competitions the token can read and their teams) when the stored catalogue is older
     * than [CATALOGUE_KEEP], or when [force]d: one request for the competitions and one per competition. A competition the
     * plan refuses is skipped; a refused token or a rate limit stops it. Never while something plays.
     */
    public suspend fun catalogue(force: Boolean = false): DomainError? {
        val token = token() ?: return null
        val fetched = sport.fetchedAt(CATALOGUE)
        if (!force && fetched != null && clock.now() - fetched < CATALOGUE_KEEP) return null
        if (playing()) return null
        val (competitions, error) = spaced { football.competitions(token) }
        if (error != null) return error
        sport.saveCompetitions(competitions.map { SportCompetition(it.code, it.name, it.area, it.emblem) })
        for (competition in competitions) {
            if (playing()) return null
            val (teams, teamsError) = spaced { football.competitionTeams(token, competition.code) }
            if (teamsError != null) {
                if (stops(teamsError)) return teamsError
                continue
            }
            teams.forEach { team -> if (sport.team(team.id.toString()) == null) sport.saveTeam(team.toTeam()) }
            sport.saveMemberships(competition.code, teams.map { it.id.toString() })
        }
        sport.setFetched(CATALOGUE)
        return null
    }

    /** One press (x12): follows [ref] and reads its matches (and, for a team, its names in Arabic and French) at once. */
    public suspend fun follow(kind: FollowKind, ref: String, name: String): DomainError? {
        sport.follow(kind, ref, name)
        val token = token() ?: return null
        return refreshOne(token, kind, ref, name, today())
    }

    /**
     * Reads one team's matches without following it, for a team the viewer searched for (4.16), when they were last read
     * in an earlier six-hour slot. Nothing without a token or while something plays. True when matches were read; an
     * error is returned as false, with what was stored kept.
     */
    public suspend fun teamFixtures(ref: String): Boolean {
        val token = token() ?: return false
        val id = ref.toLongOrNull() ?: return false
        val fetched = sport.fetchedAt("$TEAM$ref")
        if (fetched != null && slot(fetched) == slot(clock.now())) return false
        if (playing()) return false
        val day = today()
        val (matches, error) = spaced { football.teamMatches(token, id, day.from, day.to) }
        if (error != null) return false
        saveMatches(matches)
        sport.setFetched("$TEAM$ref")
        return true
    }

    /**
     * Reads [competition]'s table when it was last read in an earlier six-hour slot (a league opened without being
     * followed, from a match's menu). True when it was read; nothing without a token or while something plays.
     */
    public suspend fun table(competition: String): Boolean {
        val token = token() ?: return false
        val fetched = sport.standingFetchedAt(competition)
        if (fetched != null && slot(fetched) == slot(clock.now())) return false
        if (playing()) return false
        val (table, error) = spaced { football.standings(token, competition) }
        if (error != null) return false
        sport.saveStanding(
            competition,
            table.map { StandingRow(it.position, it.team.id.toString(), it.team.name, it.played, it.points, it.goalDiff) },
        )
        table.forEach { row -> if (sport.team(row.team.id.toString()) == null) sport.saveTeam(row.team.toTeam()) }
        return true
    }

    public fun unfollow(kind: FollowKind, ref: String) {
        sport.unfollow(kind, ref)
    }

    /**
     * Reads what the viewer follows again when the last full reading was in an earlier six-hour slot (or when [force]d).
     * Stops at the first error that concerns the token or the service as a whole (refused, rate limited), keeping what was
     * read; stops quietly when something starts playing. Returns that error, or null.
     */
    public suspend fun refresh(force: Boolean = false): DomainError? {
        val token = token() ?: return null
        val now = clock.now()
        val last = sport.fetchedAt(FULL)
        if (!force && last != null && slot(last) == slot(now)) return null
        val day = today()
        for (followed in sport.follows()) {
            if (playing()) return null
            refreshOne(token, followed.kind, followed.ref, followed.name, day)?.let { error ->
                if (stops(error)) return error
            }
        }
        if (playing()) return null
        sport.dropFixturesBefore(now - KEEP_PAST)
        sport.setFetched(FULL)
        return null
    }

    private suspend fun refreshOne(token: Secret<String>, kind: FollowKind, ref: String, name: String, day: Window): DomainError? =
        when (kind) {
            FollowKind.COMPETITION -> {
                val (matches, error) = spaced { football.matches(token, ref, day.from, day.to) }
                if (error != null) {
                    error
                } else {
                    saveMatches(matches)
                    sport.setFetched("$COMPETITION$ref")
                    if (playing()) {
                        null
                    } else {
                        val (table, tableError) = spaced { football.standings(token, ref) }
                        // A cup has no table: an empty one is kept as such, not as an error.
                        if (tableError == null) {
                            sport.saveStanding(
                                ref,
                                table.map {
                                    StandingRow(it.position, it.team.id.toString(), it.team.name, it.played, it.points, it.goalDiff)
                                },
                            )
                        }
                        tableError
                    }
                }
            }
            FollowKind.TEAM -> {
                val id = ref.toLongOrNull()
                if (id == null) {
                    null
                } else {
                    val (matches, error) = spaced { football.teamMatches(token, id, day.from, day.to) }
                    if (error != null) {
                        error
                    } else {
                        saveMatches(matches)
                        sport.setFetched("$TEAM$ref")
                        if (!playing()) names(ref, name)
                        null
                    }
                }
            }
        }

    /** The team's names in Arabic, French and English, from Wikidata, once every [NAMES_KEEP]; a failure is quiet. */
    private suspend fun names(ref: String, name: String) {
        val resource = "$NAMES$ref"
        val fetched = sport.fetchedAt(resource)
        if (fetched != null && clock.now() - fetched < NAMES_KEEP) return
        val (names, error) = spaced { wikidata.names(name) }
        if (error != null) return
        val team = sport.team(ref) ?: SportTeam(ref, name, null, null, null)
        sport.saveTeam(team, names.map { it.text }, SportStore.ORIGIN_WIKIDATA)
        sport.setFetched(resource)
    }

    private fun saveMatches(matches: List<FootballMatch>) {
        // The teams too, so a match's teams are searchable by name even when only the competition is followed.
        matches.flatMap { listOf(it.home, it.away) }.distinctBy { it.id }.forEach { team ->
            val known = sport.team(team.id.toString())
            if (known == null || known.crestUrl == null) sport.saveTeam(team.toTeam())
        }
        sport.saveFixtures(
            matches.map {
                Fixture(
                    it.id.toString(),
                    it.competitionCode,
                    it.competitionName,
                    it.home.id.toString(),
                    it.away.id.toString(),
                    it.home.name,
                    it.away.name,
                    it.kickoffUtc,
                    it.status,
                )
            },
        )
    }

    /** Runs [request] no sooner than [spacing] after the one before it. */
    private suspend fun <T> spaced(request: suspend () -> T): T {
        lastRequest?.let { last ->
            val wait = spacing - (clock.now() - last)
            if (wait > Duration.ZERO) pause(wait)
        }
        try {
            return request()
        } finally {
            lastRequest = clock.now()
        }
    }

    private suspend fun token(): Secret<String>? = secrets.get(TOKEN)?.password

    /** The six-hour slot of the viewer's day that [at] falls in. */
    private fun slot(at: Instant): Long = (at + utcOffset(at)).toEpochMilliseconds().floorDiv(SLOT.inWholeMilliseconds)

    /** From yesterday to two weeks ahead, as football-data.org's dates (UTC days). */
    private fun today(): Window {
        val now = clock.now()
        return Window(day(now - KEEP_PAST), day(now + AHEAD))
    }

    private fun day(at: Instant): String = at.toString().take(DATE_LENGTH)

    private fun stops(error: DomainError): Boolean = error is DomainError.Auth || error == FootballDataClient.RATE_LIMITED

    private fun FootballTeam.toTeam() = SportTeam(id.toString(), name, shortName, tla, crest)

    private class Window(val from: String, val to: String)

    public companion object {
        private val TOKEN = CredentialRef("football-data-token")

        /** football-data.org tokens are 32 hexadecimal characters; anything far from that is a paste error. */
        private val TOKEN_LENGTHS = 16..128
        private val SLOT = 6.hours
        private val AHEAD = 14.days
        private val KEEP_PAST = 1.days
        private val NAMES_KEEP = 90.days
        private val CATALOGUE_KEEP = 7.days
        private const val CATALOGUE = "catalogue"

        /** Eight requests a minute at most: the free plan allows ten. */
        public val REQUEST_SPACING: Duration = 7_500.milliseconds
        private const val DATE_LENGTH = 10
        private const val FULL = "full"
        private const val COMPETITION = "competition:"
        private const val TEAM = "team:"
        private const val NAMES = "names:"
    }
}
