package app.iptvplayer.storage

import app.iptvplayer.domain.ports.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Sport's own memory (ADR-0057): follows, team names in any script, fixtures, tables and reminders. */
class SportStoreTest {
    private val file = TestDatabaseFile("sport-store")
    private val driver = openIptvDatabase(file.path)
    private var now = Instant.parse("2026-10-29T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private val sport = SportStore(ContentStore(driver, clock), clock)

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private val city = SportTeam("65", "Example City FC", "Example City", "EXC", "https://football.example.com/crests/65.png")
    private val united = SportTeam("66", "Sample United", "Sample Utd", "SAM", null)

    private fun match(id: String, kickoff: Instant, home: SportTeam = city, away: SportTeam = united) =
        Fixture(id, "EL", "Example League", home.ref, away.ref, home.name, away.name, kickoff, "TIMED")

    @Test
    fun followingIsOnePressAndUnfollowingForgetsIt() {
        sport.follow(FollowKind.TEAM, city.ref, city.name)
        sport.follow(FollowKind.COMPETITION, "EL", "Example League")
        assertEquals(listOf(FollowKind.TEAM, FollowKind.COMPETITION), sport.follows().map { it.kind })
        assertTrue(sport.isFollowed(FollowKind.TEAM, city.ref))
        sport.unfollow(FollowKind.TEAM, city.ref)
        assertFalse(sport.isFollowed(FollowKind.TEAM, city.ref))
        assertEquals(listOf("EL"), sport.follows().map { it.ref })
    }

    @Test
    fun aTeamIsFoundByItsNameInArabicOrLatinLetters() {
        // "Mithal Siti", the team's name in Arabic letters, as Wikidata would give it.
        sport.saveTeam(city, listOf("مثال سيتي"), SportStore.ORIGIN_WIKIDATA)
        sport.saveTeam(united)
        assertEquals(listOf(city.ref), sport.teamsMatching("example"))
        assertEquals(listOf(city.ref), sport.teamsMatching("مثال"))
        assertEquals(listOf(united.ref), sport.teamsMatching("SAMPLE utd"))
        assertEquals(emptyList(), sport.teamsMatching("ex"), "two letters find nothing")
    }

    @Test
    fun fixturesAreReadByTimeAndByTeam() {
        val other = SportTeam("67", "Test Rovers", null, null, null)
        sport.saveFixtures(
            listOf(
                match("2", now + 2.days),
                match("1", now + 1.hours),
                match("3", now + 3.days, other, united),
                match("0", now - 2.days),
            ),
        )
        assertEquals(listOf("1", "2", "3"), sport.fixtures(now, now + 14.days).map { it.id })
        assertEquals(listOf("1", "2"), sport.fixturesFor(listOf(city.ref), now, now + 14.days).map { it.id })
        // Read again: the newer reading replaces the stored match, not a second copy of it.
        sport.saveFixtures(listOf(match("1", now + 2.hours)))
        assertEquals(now + 2.hours, sport.fixture("1")?.kickoff)
        sport.dropFixturesBefore(now)
        assertEquals(null, sport.fixture("0"))
    }

    @Test
    fun aTableIsReplacedWhole() {
        sport.saveStanding(
            "EL",
            listOf(StandingRow(1, "65", "Example City FC", 10, 25, 12), StandingRow(2, "66", "Sample United", 10, 20, 5)),
        )
        sport.saveStanding("EL", listOf(StandingRow(1, "66", "Sample United", 11, 23, 6)))
        assertEquals(listOf("66"), sport.standing("EL").map { it.teamRef })
    }

    @Test
    fun remindersAreForMatchesStillToCome() {
        sport.saveFixtures(listOf(match("1", now + 1.hours), match("2", now + 2.days)))
        sport.remind("2", true)
        sport.remind("1", true)
        assertEquals(listOf("1", "2"), sport.reminders().map { it.id })
        now += 3.hours
        assertEquals(listOf("2"), sport.reminders().map { it.id })
        sport.remind("2", false)
        assertTrue(sport.reminders().isEmpty())
    }

    @Test
    fun removingTheTokenForgetsWhatWasReadButNotWhatIsFollowed() {
        sport.follow(FollowKind.TEAM, city.ref, city.name)
        sport.saveFixtures(listOf(match("1", now + 1.hours)))
        sport.saveStanding("EL", listOf(StandingRow(1, "65", "Example City FC", 1, 3, 1)))
        sport.setFetched("competition:EL")
        sport.clearFetched()
        assertTrue(sport.fixtures(now, now + 1.days).isEmpty())
        assertTrue(sport.standing("EL").isEmpty())
        assertEquals(null, sport.fetchedAt("competition:EL"))
        assertTrue(sport.isFollowed(FollowKind.TEAM, city.ref))
    }
}
