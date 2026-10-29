package app.iptvplayer.ingestion

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.id.CredentialRef
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.Clock
import app.iptvplayer.domain.ports.HttpRequest
import app.iptvplayer.domain.ports.HttpResponse
import app.iptvplayer.domain.ports.HttpTimings
import app.iptvplayer.domain.ports.HttpTransport
import app.iptvplayer.domain.ports.SecretStore
import app.iptvplayer.domain.security.SecretBundle
import app.iptvplayer.protocols.football.FootballDataClient
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.FollowKind
import app.iptvplayer.storage.SportStore
import app.iptvplayer.storage.openIptvDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Sport with the viewer's own token (ADR-0057): kept only once accepted, read at most once a six-hour slot, spaced under
 * the free plan's limit, never while something plays, and stopped (keeping what was read) when the service says to wait.
 */
class SportServiceTest {
    private val file = TestDatabaseFile("sport-service")
    private val driver = openIptvDatabase(file.path)
    private var now = Instant.parse("2026-10-29T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private val sport = SportStore(ContentStore(driver, clock), clock)
    private val secrets = object : SecretStore {
        val entries = HashMap<CredentialRef, SecretBundle>()

        override suspend fun put(ref: CredentialRef, bundle: SecretBundle) {
            entries[ref] = bundle
        }

        override suspend fun get(ref: CredentialRef): SecretBundle? = entries[ref]

        override suspend fun delete(ref: CredentialRef) {
            entries.remove(ref)
        }

        override suspend fun exists(ref: CredentialRef): Boolean = ref in entries
    }

    private var rateLimited = false

    /** football-data.org and Wikidata as far as these tests need them: one competition, one team, their names. */
    private val web = object : HttpTransport {
        val paths = ArrayList<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            val url = request.url.unsafeRawValue()
            val path = url.substringAfter(".com/").substringBefore('?')
            paths += path + (url.substringAfter("action=", "").substringBefore('&').takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
            val token = request.sensitiveHeaders["X-Auth-Token"]?.unsafeValue()
            val (status, body) = when {
                path.startsWith("w/") -> 200 to wikidata(url)
                token != GOOD_TOKEN -> 403 to """{"message":"The resource you are looking for is restricted."}"""
                rateLimited -> 429 to """{"message":"You reached your request limit."}"""
                path == "v4/competitions" ->
                    200 to """{"competitions":[{"id":1,"code":"EXL","name":"Example League"},{"id":2,"code":"XCL","name":"Example Cup"}]}"""
                path == "v4/competitions/EXL/teams" -> 200 to """{"teams":[$CITY]}"""
                // Outside the free plan.
                path == "v4/competitions/XCL/teams" -> 403 to """{"message":"The resource you are looking for is restricted."}"""
                path == "v4/competitions/EXL/matches" -> 200 to """{"matches":[${match(1, "2026-11-01T20:00:00Z")}]}"""
                path == "v4/competitions/EXL/standings" ->
                    200 to """{"standings":[{"type":"TOTAL","table":[{"position":1,"team":$CITY,""" +
                        """"playedGames":10,"points":25,"goalDifference":12}]}]}"""
                path == "v4/teams/65/matches" -> 200 to """{"matches":[${match(2, "2026-11-05T19:45:00Z")}]}"""
                else -> 404 to "{}"
            }
            return HttpResponse(status, emptyMap(), request.url, ByteArraySource(body.encodeToByteArray()), HttpTimings(0, 0))
        }

        private fun match(id: Int, kickoff: String) =
            """{"id":$id,"utcDate":"$kickoff","status":"TIMED","competition":{"code":"EXL","name":"Example League"},""" +
                """"homeTeam":$CITY,"awayTeam":{"id":66,"name":"Sample United","shortName":"Sample Utd","tla":"SAM"}}"""

        private fun wikidata(url: String) = if ("wbsearchentities" in url) {
            """{"search":[{"id":"Q1","label":"Example City FC","description":"association football club"}]}"""
        } else {
            // The team's name in Arabic letters.
            """{"entities":{"Q1":{"labels":{"ar":{"language":"ar","value":"\u0645\u062b\u0627\u0644 \u0633\u064a\u062a\u064a"}}}}}"""
        }
    }

    private val pauses = ArrayList<Duration>()
    private var playing = false
    private val service = SportService(
        web,
        secrets,
        sport,
        clock,
        playing = { playing },
        pause = { pauses += it },
        footballBase = { "https://football.example.com/v4" },
        wikidataBase = { "https://wikidata.example.com/w/api.php" },
    )

    @AfterTest
    fun cleanUp() {
        driver.close()
        file.delete()
    }

    private suspend fun withToken() {
        assertNull(service.setToken(GOOD_TOKEN))
        web.paths.clear()
        pauses.clear()
    }

    @Test
    fun aTokenIsKeptOnlyWhenAccepted() = runTest {
        assertIs<DomainError.Auth>(service.setToken("short"))
        assertIs<DomainError.Auth>(service.setToken("0123456789abcdef0123456789abcdef"))
        assertFalse(service.hasToken())
        assertNull(service.setToken("  $GOOD_TOKEN  "), "surrounding spaces from a paste are ignored")
        assertTrue(service.hasToken())
    }

    @Test
    fun followingReadsAtOnceAndARefreshReadsOncePerSlot() = runTest {
        withToken()
        assertNull(service.follow(FollowKind.COMPETITION, "EXL", "Example League"))
        assertNull(service.follow(FollowKind.TEAM, "65", "Example City FC"))
        assertEquals(
            listOf(
                "v4/competitions/EXL/matches",
                "v4/competitions/EXL/standings",
                "v4/teams/65/matches",
                "w/api.php:wbsearchentities",
                "w/api.php:wbgetentities",
            ),
            web.paths,
        )
        assertEquals(listOf("1", "2"), sport.fixtures(now, now + 14.days).map { it.id })
        assertEquals(listOf("65"), sport.standing("EXL").map { it.teamRef })
        assertEquals(listOf("65"), sport.teamsMatching("\u0645\u062b\u0627\u0644"), "found by its Arabic name")

        web.paths.clear()
        assertNull(service.refresh())
        assertEquals(3, web.paths.size, "the names are kept 90 days: not asked again")
        web.paths.clear()
        now += 1.hours
        assertNull(service.refresh())
        assertTrue(web.paths.isEmpty(), "same six-hour slot (06:00 to noon)")
        now += 2.hours
        assertNull(service.refresh())
        assertEquals(3, web.paths.size, "past noon: the next slot")
    }

    @Test
    fun requestsAreSpacedUnderTheFreePlansLimit() = runTest {
        withToken()
        service.follow(FollowKind.COMPETITION, "EXL", "Example League")
        // The clock stands still in this test, so every request after the first waits the whole spacing.
        assertEquals(listOf(SportService.REQUEST_SPACING, SportService.REQUEST_SPACING), pauses)
    }

    @Test
    fun aRateLimitStopsTheRefreshAndKeepsWhatWasRead() = runTest {
        withToken()
        service.follow(FollowKind.COMPETITION, "EXL", "Example League")
        service.follow(FollowKind.TEAM, "65", "Example City FC")
        rateLimited = true
        web.paths.clear()
        assertEquals(FootballDataClient.RATE_LIMITED, service.refresh(force = true))
        assertEquals(listOf("v4/competitions/EXL/matches"), web.paths.distinct(), "stopped at the first refusal")
        assertEquals(2, sport.fixtures(now, now + 14.days).size)
    }

    @Test
    fun nothingIsReadWhileSomethingPlays() = runTest {
        withToken()
        sport.follow(FollowKind.COMPETITION, "EXL", "Example League")
        playing = true
        assertNull(service.refresh(force = true))
        assertTrue(web.paths.isEmpty())
        assertNull(sport.fetchedAt("full"), "a stopped refresh is tried again later")
    }

    @Test
    fun removingTheTokenForgetsWhatWasReadButKeepsWhatIsFollowed() = runTest {
        withToken()
        service.follow(FollowKind.COMPETITION, "EXL", "Example League")
        service.removeToken()
        assertFalse(service.hasToken())
        assertTrue(sport.fixtures(now, now + 14.days).isEmpty())
        assertTrue(sport.isFollowed(FollowKind.COMPETITION, "EXL"))
    }

    @Test
    fun theCatalogueListsWhatCanBeFollowedAndSkipsWhatThePlanRefuses() = runTest {
        withToken()
        assertNull(service.catalogue())
        assertEquals(listOf("v4/competitions", "v4/competitions/EXL/teams", "v4/competitions/XCL/teams"), web.paths)
        assertEquals(listOf("XCL", "EXL"), sport.competitions().map { it.code }, "by name")
        assertEquals(listOf("65"), sport.followable(sport.teamsMatching("example city")).map { it.ref })
        web.paths.clear()
        assertNull(service.catalogue())
        assertTrue(web.paths.isEmpty(), "kept a week")
    }

    private companion object {
        const val GOOD_TOKEN = "f00dfeed0123456789abcdef0a1b2c3d"
        const val CITY = """{"id":65,"name":"Example City FC","shortName":"Example City","tla":"EXC"}"""
    }
}
