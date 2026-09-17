package app.iptvplayer.protocols.tmdb

import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.library.ExternalList
import app.iptvplayer.domain.library.TitleCleaner
import app.iptvplayer.domain.model.ContentType
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

class TmdbClientTest {
    private val key = Secret("tmdb-canary-key-3f9a")

    private fun client(route: (String) -> FakeTransport.Reply): Pair<TmdbClient, FakeTransport> {
        val transport = FakeTransport { request -> route(request.url.unsafeRawValue().substringAfter("/3/").substringBefore('?')) }
        return TmdbClient(HttpFetcher(transport, jitter = { 0.0 }, sleep = {}), base = "https://tmdb.example.com/3") to transport
    }

    @Test
    fun aListIsReadSkippingEntriesWithoutAnIdOrATitle() = runTest {
        val (client, transport) = client { path ->
            if (path == "trending/movie/week") FakeTransport.ok(Fixtures.tmdbTrendingMoviesJson) else FakeTransport.status(404)
        }
        val (titles, error) = client.page(key, ExternalList.TRENDING_MOVIES, 1)
        assertNull(error)
        assertEquals(listOf("Example Heist", "The Example Voyage", "Unrated Example"), titles.map { it.title })
        val heist = titles.first()
        assertEquals(TmdbTitle(900001, ContentType.MOVIE, "Example Heist", 2024, 7.8, 1520), heist)
        assertEquals(
            listOf("tmdb:900001", TitleCleaner.workKey("Example Heist", 2024), TitleCleaner.workKey("Example Heist", null)),
            heist.workKeys,
        )
        assertNull(titles.last().year, "an empty release date is no year")
        assertNull(titles.last().rating, "a zero rating is no rating")

        // The key is a query parameter of a sensitive URL: sent, but never part of what the URL prints.
        val url = transport.requests.single().url
        assertTrue(url.unsafeRawValue().contains("api_key=tmdb-canary-key-3f9a"))
        assertTrue(url.unsafeRawValue().contains("page=1"))
        assertFalse(url.toString().contains("canary"))
        assertEquals(0, transport.openBodies)
    }

    @Test
    fun showsUseNamesAndFirstAirDates() = runTest {
        val (client, _) = client { FakeTransport.ok(Fixtures.tmdbTopRatedTvJson) }
        val (titles, _) = client.page(key, ExternalList.TOP_SERIES, 1)
        assertEquals(TmdbTitle(910001, ContentType.SERIES, "Example Chronicles", 2019, 8.7, 4012), titles.first())
    }

    @Test
    fun aRefusedKeyIsAnAuthErrorAndAnHtmlPageIsNotAList() = runTest {
        val (refused, _) = client { FakeTransport.status(401, """{"status_code":7,"status_message":"Invalid API key"}""") }
        assertIs<DomainError.Auth>(refused.check(key))
        val (html, _) = client { FakeTransport.ok("<html><body>maintenance</body></html>") }
        assertIs<DomainError.Parse>(html.page(key, ExternalList.POPULAR_MOVIES, 1).second)
        val (ok, _) = client { FakeTransport.ok("""{"images":{}}""") }
        assertNull(ok.check(key))
    }
}
