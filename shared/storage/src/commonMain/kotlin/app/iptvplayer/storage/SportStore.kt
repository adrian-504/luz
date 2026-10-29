package app.iptvplayer.storage

import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.text.SearchFold
import kotlin.time.Instant

/** What the viewer follows: a team or a whole competition (x12). */
public enum class FollowKind { TEAM, COMPETITION }

public data class Followed(public val kind: FollowKind, public val ref: String, public val name: String)

public data class SportTeam(
    public val ref: String,
    public val name: String,
    public val shortName: String?,
    public val tla: String?,
    public val crestUrl: String?,
)

/** A competition the viewer's token can read: one that can be followed. */
public data class SportCompetition(
    public val code: String,
    public val name: String,
    public val area: String?,
    public val emblemUrl: String?,
)

/** One match from the fixtures source, as it was last read. Never a channel: Luz makes no channel claims (ADR-0057). */
public data class Fixture(
    public val id: String,
    public val competition: String,
    public val competitionName: String,
    public val homeRef: String,
    public val awayRef: String,
    public val homeName: String,
    public val awayName: String,
    public val kickoff: Instant,
    public val status: String,
)

public data class StandingRow(
    public val position: Int,
    public val teamRef: String,
    public val teamName: String,
    public val played: Int,
    public val points: Int,
    public val goalDiff: Int,
)

/**
 * Sport's own memory (ADR-0057): what the viewer follows, the teams' names in every language Luz learned, the fixtures and
 * tables read with the viewer's token, and reminders. Not per source: following a team is the viewer's, whatever they
 * watch it on. Team names are kept in SearchFold's plain spellings (lower case, Arabic letter forms joined), and every
 * name Luz learned is kept, so a search in Arabic or Latin letters finds the same team.
 */
public class SportStore(private val content: ContentStore, private val clock: Clock) {
    private val queries get() = content.sportQueries

    public fun follows(): List<Followed> = queries.follows().executeAsList().mapNotNull { row ->
        val kind = FollowKind.entries.firstOrNull { it.name == row.kind } ?: return@mapNotNull null
        Followed(kind, row.ref, row.name)
    }

    public fun follow(kind: FollowKind, ref: String, name: String) {
        queries.follow(kind.name, ref, name, clock.now().toEpochMilliseconds())
    }

    public fun unfollow(kind: FollowKind, ref: String) {
        queries.unfollow(kind.name, ref)
    }

    public fun isFollowed(kind: FollowKind, ref: String): Boolean = queries.isFollowed(kind.name, ref).executeAsOne() > 0

    /** Keeps [team] and its own names as search keys; [names] adds names in other languages from [origin]. */
    public fun saveTeam(team: SportTeam, names: Collection<String> = emptyList(), origin: String = ORIGIN_SOURCE) {
        val now = clock.now().toEpochMilliseconds()
        content.database.transaction {
            queries.saveTeam(team.ref, team.name, team.shortName, team.tla, team.crestUrl, now)
            listOfNotNull(team.name, team.shortName).forEach { addAlias(team.ref, it, ORIGIN_SOURCE) }
            names.forEach { addAlias(team.ref, it, origin) }
        }
    }

    private fun addAlias(teamRef: String, name: String, origin: String) {
        val folded = plain(name)
        if (folded.isNotBlank()) queries.addAlias(teamRef, folded, origin)
    }

    public fun team(ref: String): SportTeam? =
        queries.team(ref).executeAsOneOrNull()?.let { SportTeam(it.ref, it.name, it.short_name, it.tla, it.crest_url) }

    /** When [ref]'s names were last read, to read them again after a while; null when never. */
    public fun teamFetchedAt(ref: String): Instant? =
        queries.team(ref).executeAsOneOrNull()?.let { Instant.fromEpochMilliseconds(it.fetched_at) }

    /** Every folded name Luz knows for the team, in any language. */
    public fun aliases(teamRef: String): List<String> = queries.aliasesOf(teamRef).executeAsList()

    /** Teams one of whose names contains [query], folded first; nothing for a query shorter than [MIN_QUERY] letters. */
    public fun teamsMatching(query: String): List<String> {
        val folded = plain(query)
        if (folded.length < MIN_QUERY) return emptyList()
        return queries.teamsMatching(folded).executeAsList()
    }

    /** Replaces the catalogue of competitions the token can read. */
    public fun saveCompetitions(competitions: List<SportCompetition>) {
        val now = clock.now().toEpochMilliseconds()
        content.database.transaction {
            queries.clearCatalogue()
            competitions.forEach { queries.saveCompetition(it.code, it.name, it.area, it.emblemUrl, now) }
        }
    }

    public fun competitions(): List<SportCompetition> =
        queries.competitions().executeAsList().map { SportCompetition(it.code, it.name, it.area, it.emblem_url) }

    /** Notes that [teamRefs] play in [competition]. */
    public fun saveMemberships(competition: String, teamRefs: Collection<String>) {
        content.database.transaction { teamRefs.forEach { queries.addMembership(it, competition) } }
    }

    /** The competitions [teamRef] plays in, of those Luz can read: "Follow the league" on a team's match. */
    public fun competitionsOf(teamRef: String): List<SportCompetition> =
        queries.competitionsOf(teamRef).executeAsList().map { SportCompetition(it.code, it.name, it.area, it.emblem_url) }

    /** Of [refs], the teams in a competition Luz can read: the ones that can be followed. */
    public fun followable(refs: Collection<String>): List<SportTeam> {
        if (refs.isEmpty()) return emptyList()
        return queries.catalogueTeams(refs).executeAsList().map { SportTeam(it.ref, it.name, it.short_name, it.tla, it.crest_url) }
    }

    /** Competitions whose name holds [query] (plain spellings); nothing under [MIN_QUERY] letters. */
    public fun competitionsMatching(query: String): List<SportCompetition> {
        val folded = plain(query)
        if (folded.length < MIN_QUERY) return emptyList()
        return competitions().filter { folded in plain(it.name) }
    }

    /** Stores [fixtures] as read now, replacing what was stored for the same matches. */
    public fun saveFixtures(fixtures: List<Fixture>) {
        val now = clock.now().toEpochMilliseconds()
        content.database.transaction {
            fixtures.forEach {
                queries.saveFixture(
                    it.id,
                    it.competition,
                    it.competitionName,
                    it.homeRef,
                    it.awayRef,
                    it.homeName,
                    it.awayName,
                    it.kickoff.toEpochMilliseconds(),
                    it.status,
                    now,
                )
            }
        }
    }

    public fun fixture(id: String): Fixture? = queries.fixture(id).executeAsOneOrNull()?.toFixture()

    public fun fixtures(from: Instant, until: Instant): List<Fixture> =
        queries.fixturesBetween(from.toEpochMilliseconds(), until.toEpochMilliseconds()).executeAsList().map { it.toFixture() }

    public fun fixturesFor(teams: Collection<String>, from: Instant, until: Instant): List<Fixture> {
        if (teams.isEmpty()) return emptyList()
        return queries.fixturesForTeams(from.toEpochMilliseconds(), until.toEpochMilliseconds(), teams)
            .executeAsList()
            .map { it.toFixture() }
    }

    /** Forgets matches that kicked off before [before]. */
    public fun dropFixturesBefore(before: Instant) {
        queries.deleteFixturesBefore(before.toEpochMilliseconds())
    }

    public fun saveStanding(competition: String, rows: List<StandingRow>) {
        val now = clock.now().toEpochMilliseconds()
        content.database.transaction {
            queries.clearStanding(competition)
            rows.forEach {
                queries.addStanding(
                    competition,
                    it.position.toLong(),
                    it.teamRef,
                    it.teamName,
                    it.played.toLong(),
                    it.points.toLong(),
                    it.goalDiff.toLong(),
                    now,
                )
            }
        }
    }

    public fun standing(competition: String): List<StandingRow> = queries.standing(competition).executeAsList().map {
        StandingRow(it.position.toInt(), it.team_ref, it.team_name, it.played.toInt(), it.points.toInt(), it.goal_diff.toInt())
    }

    /** When [competition]'s table was last read; null when it never was. */
    public fun standingFetchedAt(competition: String): Instant? =
        queries.standingFetchedAt(competition).executeAsOneOrNull()?.fetched_at?.let { Instant.fromEpochMilliseconds(it) }

    public fun remind(fixtureId: String, on: Boolean) {
        if (on) queries.addReminder(fixtureId, clock.now().toEpochMilliseconds()) else queries.removeReminder(fixtureId)
    }

    /** Matches with a reminder that have not kicked off yet, soonest first. */
    public fun reminders(): List<Fixture> = queries.reminders(clock.now().toEpochMilliseconds()).executeAsList().map {
        Fixture(
            it.id,
            it.competition,
            it.competition_name,
            it.home_ref,
            it.away_ref,
            it.home_name,
            it.away_name,
            Instant.fromEpochMilliseconds(it.kickoff_utc),
            it.status,
        )
    }

    public fun fetchedAt(resource: String): Instant? =
        queries.fetchedAt(resource).executeAsOneOrNull()?.let { Instant.fromEpochMilliseconds(it) }

    public fun setFetched(resource: String) {
        queries.setFetched(resource, clock.now().toEpochMilliseconds())
    }

    /** Forgets everything read with the token (matches and their reminders, tables, the catalogue); what is followed stays. */
    public fun clearFetched() {
        content.database.transaction {
            queries.clearFetched()
            queries.clearFixtures()
            queries.clearStandings()
            queries.clearCatalogue()
            queries.clearMemberships()
            // A reminder is for a match read with the token: it goes with it.
            queries.clearReminders()
        }
    }

    private fun app.iptvplayer.storage.db.Sport_fixture.toFixture() = Fixture(
        id,
        competition,
        competition_name,
        home_ref,
        away_ref,
        home_name,
        away_name,
        Instant.fromEpochMilliseconds(kickoff_utc),
        status,
    )

    /** [text] in SearchFold's plain spellings, one space between words; no sound keys, so names match as substrings. */
    private fun plain(text: String): String = SearchFold.spellings(text).joinToString(" ")

    public companion object {
        public const val ORIGIN_SOURCE: String = "source"
        public const val ORIGIN_WIKIDATA: String = "wikidata"
        private const val MIN_QUERY = 3
    }
}
