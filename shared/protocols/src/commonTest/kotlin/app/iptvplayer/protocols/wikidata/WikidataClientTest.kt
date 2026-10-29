package app.iptvplayer.protocols.wikidata

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.protocols.fixtures.Fixtures
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.testing.FakeTransport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikidataClientTest {
    private fun client(route: (String) -> FakeTransport.Reply): Pair<WikidataClient, FakeTransport> {
        val transport = FakeTransport { request -> route(request.url.unsafeRawValue().substringAfter('?')) }
        val fetcher = HttpFetcher(transport, jitter = { 0.0 }, sleep = {})
        return WikidataClient(fetcher, base = "https://wikidata.example.com/w/api.php") to transport
    }

    private fun route(query: String): FakeTransport.Reply = when {
        "action=wbsearchentities" in query -> FakeTransport.ok(Fixtures.wikidataSearchJson)
        "action=wbgetentities" in query -> FakeTransport.ok(Fixtures.wikidataEntitiesJson)
        else -> FakeTransport.status(404)
    }

    @Test
    fun theFootballHitIsPickedAndItsArabicFrenchAndEnglishNamesRead() = runTest {
        val (client, transport) = client(::route)
        val (names, error) = client.names("Example City")
        assertNull(error)
        assertEquals(
            listOf(
                TeamName("مثال سيتي", "ar"),
                TeamName("نادي مثال", "ar"),
                TeamName("Example City FC", "fr"),
                TeamName("Example City FC", "en"),
                TeamName("Example City", "en"),
            ),
            names,
            "labels then aliases per language; repeated and blank ones left out",
        )
        assertTrue(names.all { it.origin == "wikidata" })
        assertEquals(2, transport.requests.size)
        val search = transport.requests.first().url.unsafeRawValue()
        assertTrue("search=Example%20City" in search && "limit=5" in search && "type=item" in search)
        val entities = transport.requests.last().url.unsafeRawValue()
        assertTrue("ids=Q900002" in entities, "the football club, not the city, is looked up")
        assertTrue("languages=ar%7Cfr%7Cen" in entities)
        for (request in transport.requests) {
            assertEquals(WikidataClient.USER_AGENT, request.headers["User-Agent"])
        }
    }

    @Test
    fun noFootballHitMeansNoNamesNoErrorAndNoSecondRequest() = runTest {
        val cityOnly = """{"search":[{"id":"Q1","label":"Example","description":"city in Exampleland"}]}"""
        val (client, transport) = client { FakeTransport.ok(cityOnly) }
        val (names, error) = client.names("Example")
        assertNull(error)
        assertTrue(names.isEmpty())
        assertEquals(1, transport.requests.size)
        val (nothing, _) = client { FakeTransport.ok("""{"search":[]}""") }
        assertTrue(nothing.names("Nobody").first.isEmpty())
    }

    @Test
    fun aFailureOrAnHtmlPageIsAnError() = runTest {
        val (down, _) = client { FakeTransport.status(500) }
        assertIs<DomainError.Http>(down.names("Example City").second)
        val (html, _) = client { FakeTransport.ok("<html><body>maintenance</body></html>") }
        assertIs<DomainError.Parse>(html.names("Example City").second)
    }
}
