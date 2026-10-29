package app.iptvplayer.protocols.football

import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.protocols.fixtures.Fixtures
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FootballDataClientTest {
    private val token = Secret("football-canary-token-8c1d")

    private fun client(route: (String) -> FakeTransport.Reply): Pair<FootballDataClient, FakeTransport> {
        val transport = FakeTransport { request -> route(request.url.unsafeRawValue().substringAfter("/v4/").substringBefore('?')) }
        val fetcher = HttpFetcher(transport, jitter = { 0.0 }, sleep = {})
        return FootballDataClient(fetcher, base = "https://football.example.com/v4") to transport
    }

    private val exampleCity = FootballTeam(
        101,
        "Example City FC",
        "Example City",
        "EXC",
        "https://football.example.com/crests/1.png",
    )
    private val sampleUnited = FootballTeam(
        102,
        "Sample United",
        "Sample Utd",
        "SMU",
        "https://football.example.com/crests/2.png",
    )

    @Test
    fun competitionsAreReadSkippingOnesWithoutACode() = runTest {
        val (client, transport) = client { FakeTransport.ok(Fixtures.footballCompetitionsJson) }
        val (competitions, error) = client.competitions(token)
        assertNull(error)
        assertEquals(
            listOf(
                FootballCompetition(9001, "EXL", "Example League", "Exampleland", "https://football.example.com/crests/league-1.png"),
                FootballCompetition(9002, "SMC", "Sample Cup", "Sampleland", null),
            ),
            competitions,
        )
        assertEquals(0, transport.openBodies)
    }

    @Test
    fun theTokenTravelsOnlyInTheHeaderNeverInTheUrl() = runTest {
        val (client, transport) = client { FakeTransport.ok(Fixtures.footballCompetitionsJson) }
        assertNull(client.check(token))
        client.matches(token, "EXL", "2026-11-01", "2026-11-08")
        assertEquals(2, transport.requests.size)
        for (request in transport.requests) {
            assertEquals("football-canary-token-8c1d", request.sensitiveHeaders["X-Auth-Token"]?.unsafeValue())
            assertTrue(request.headers.keys.none { it.equals("X-Auth-Token", ignoreCase = true) }, "never a plain header")
            assertFalse(request.url.unsafeRawValue().contains("canary"), "the token is not part of the URL")
            assertFalse(request.url.toString().contains("canary"))
        }
        assertFalse(token.toString().contains("canary"))
    }

    @Test
    fun aRefusedTokenIsAnAuthErrorAndATooBusyApiIsRecognisable() = runTest {
        val (refused, _) = client { FakeTransport.status(401, """{"message":"Your API token is invalid."}""") }
        assertEquals(DomainError.Auth(AuthFailure.INVALID_CREDENTIALS), refused.check(token))
        val (forbidden, _) = client { FakeTransport.status(403, """{"message":"The resource you are looking for is restricted."}""") }
        assertIs<DomainError.Auth>(forbidden.competitions(token).second)
        val (busy, transport) = client { FakeTransport.status(429, """{"message":"You reached your request limit."}""") }
        assertEquals(FootballDataClient.RATE_LIMITED, busy.check(token))
        assertTrue(FootballDataClient.RATE_LIMITED.retryable)
        assertTrue(transport.requests.size > 1, "the fetcher retries a 429 before giving up")
        val (html, _) = client { FakeTransport.ok("<html><body>maintenance</body></html>") }
        assertIs<DomainError.Parse>(html.competitions(token).second)
    }

    @Test
    fun teamsOfACompetitionSkipOnesWithoutAnId() = runTest {
        val (client, transport) = client { path ->
            if (path == "competitions/EXL/teams") FakeTransport.ok(Fixtures.footballTeamsJson) else FakeTransport.status(404)
        }
        val (teams, error) = client.competitionTeams(token, "EXL")
        assertNull(error)
        assertEquals(listOf(exampleCity, sampleUnited), teams)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun matchesAreReadAndATeamStillToBeDecidedIsSkipped() = runTest {
        val (client, transport) = client { path ->
            if (path == "competitions/EXL/matches") FakeTransport.ok(Fixtures.footballCompetitionMatchesJson) else FakeTransport.status(404)
        }
        val (matches, error) = client.matches(token, "EXL", "2026-11-01", "2026-11-08")
        assertNull(error)
        assertEquals(listOf(5001L, 5002L), matches.map { it.id }, "the knockout slot with no away team id is left out")
        val first = matches.first()
        assertEquals(
            FootballMatch(5001, "EXL", "Example League", exampleCity, sampleUnited, Instant.parse("2026-11-01T20:00:00Z"), "TIMED"),
            first,
        )
        assertNull(matches.last().home.shortName, "a missing short name stays null")
        val url = transport.requests.single().url.unsafeRawValue()
        assertTrue("dateFrom=2026-11-01" in url && "dateTo=2026-11-08" in url)
    }

    @Test
    fun aTeamsMatchesSpanCompetitions() = runTest {
        val (client, transport) = client { path ->
            if (path == "teams/101/matches") FakeTransport.ok(Fixtures.footballTeamMatchesJson) else FakeTransport.status(404)
        }
        val (matches, error) = client.teamMatches(token, 101, "2026-11-01", "2026-11-30")
        assertNull(error)
        assertEquals(listOf("EXL", "SMC"), matches.map { it.competitionCode })
        assertEquals("Sample Cup", matches.last().competitionName)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun aDateThatIsNotYyyyMmDdIsRefusedBeforeAnyRequest() = runTest {
        val (client, transport) = client { FakeTransport.ok(Fixtures.footballCompetitionMatchesJson) }
        assertIs<DomainError.Parse>(client.matches(token, "EXL", "01/11/2026", "2026-11-08").second)
        assertIs<DomainError.Parse>(client.teamMatches(token, 101, "2026-11-01", "tomorrow").second)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun standingsUseTheTotalTableOnly() = runTest {
        val (client, _) = client { path ->
            if (path == "competitions/EXL/standings") FakeTransport.ok(Fixtures.footballStandingsJson) else FakeTransport.status(404)
        }
        val (rows, error) = client.standings(token, "EXL")
        assertNull(error)
        assertEquals(
            listOf(FootballStanding(1, exampleCity, 10, 25, 14), FootballStanding(2, sampleUnited, 10, 22, -3)),
            rows,
            "the HOME table is not mixed in",
        )
    }
}
