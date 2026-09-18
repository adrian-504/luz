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

    @Test
    fun aTitleIsFoundByNameAndYearAndItsArtworkAndPeopleRead() = runTest {
        val (client, transport) = client { path ->
            when {
                path == "search/movie" -> FakeTransport.ok("""{"results":[{"id":77,"title":"Example Heist"},{"id":78}]}""")
                path == "movie/77" -> FakeTransport.ok(
                    """{"id":77,"images":{"logos":[{"file_path":"/fr.png","iso_639_1":"fr"},{"file_path":"/en.png","iso_639_1":"en"}]},""" +
                        """"credits":{"cast":[{"id":1,"name":"Alex Example","profile_path":"/alex.jpg"},{"id":2,"name":""}],""" +
                        """"crew":[{"id":3,"name":"Jordan Sample","job":"Director","profile_path":null},{"id":4,"name":"Pat Editor","job":"Editor"}]}}""",
                )
                else -> FakeTransport.status(404)
            }
        }
        val (id, _) = client.find(key, ContentType.MOVIE, "Example Heist", 2024)
        assertEquals(77, id)
        assertTrue("year=2024" in transport.requests.first().url.unsafeRawValue(), "the year narrows the search")
        val (art, error) = client.artwork(key, ContentType.MOVIE, 77)
        assertNull(error)
        assertEquals("/en.png", art?.logoPath, "English artwork first")
        assertEquals(
            listOf(TmdbCredit(3, "Jordan Sample", null, true), TmdbCredit(1, "Alex Example", "/alex.jpg", false)),
            art?.credits,
            "the director, then the cast; nameless entries and other crew left out",
        )
        assertEquals("https://image.tmdb.org/t/p/w500/en.png", TmdbClient.imageUrl("/en.png", "w500"))
    }

    @Test
    fun aPersonIsFoundByNameWithTheirPortraitAndBiography() = runTest {
        val (client, _) = client { path ->
            when (path) {
                "search/person" -> FakeTransport.ok("""{"results":[{"id":9,"name":"Alex Example","profile_path":"/a.jpg"}]}""")
                "person/9" -> FakeTransport.ok("""{"id":9,"biography":"An example actor."}""")
                else -> FakeTransport.status(404)
            }
        }
        assertEquals(TmdbPerson(9, "Alex Example", "/a.jpg", "An example actor."), client.person(key, "Alex Example").first)
        val (nobody, _) = client { FakeTransport.ok("""{"results":[]}""") }
        assertNull(nobody.person(key, "Nobody").first)
    }
}
