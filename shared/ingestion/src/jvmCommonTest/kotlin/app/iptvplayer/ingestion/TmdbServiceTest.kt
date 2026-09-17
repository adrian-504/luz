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
import app.iptvplayer.storage.BundledSqliteDriver
import app.iptvplayer.storage.ContentStore
import app.iptvplayer.storage.LibraryStore
import app.iptvplayer.storage.db.IptvDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** TMDB with the viewer's own key (ADR-0038): kept only once TMDB accepts it, lists read at most daily, forgotten on removal. */
class TmdbServiceTest {
    private val file: File = File.createTempFile("tmdb-service", ".db").also { it.delete() }
    private val driver = BundledSqliteDriver.open(file.path, IptvDatabase.Schema)
    private var now = Instant.parse("2026-09-17T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = now

        override fun monotonicNanos(): Long = 0
    }
    private val library = LibraryStore(ContentStore(driver, clock), clock)
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

    /** TMDB as far as these tests need it: one accepted key, one film per list page 1 and nothing after. */
    private val tmdb = object : HttpTransport {
        val paths = ArrayList<String>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            val url = request.url.unsafeRawValue()
            val path = url.substringAfter("/3/").substringBefore('?')
            paths += path
            val (status, body) = when {
                "api_key=$GOOD_KEY" !in url -> 401 to """{"status_code":7}"""
                path == "configuration" -> 200 to "{}"
                "page=1" in url ->
                    200 to
                        """{"page":1,"results":[{"id":42,"title":"Example $path","release_date":"2020-01-01","vote_average":7.5}]}"""
                else -> 200 to """{"page":2,"results":[]}"""
            }
            return HttpResponse(status, emptyMap(), request.url, ByteArraySource(body.encodeToByteArray()), HttpTimings(0, 0))
        }
    }

    private val service = TmdbService(tmdb, secrets, library, clock, base = { "https://tmdb.example.com/3" })

    @AfterTest
    fun cleanUp() {
        driver.close()
        listOf("", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    @Test
    fun aKeyIsKeptOnlyWhenTmdbAcceptsIt() = runTest {
        assertIs<DomainError.Auth>(service.setKey("short"))
        assertIs<DomainError.Auth>(service.setKey("0123456789abcdef0123456789abcdef"))
        assertFalse(service.hasKey())
        assertNull(service.setKey("  $GOOD_KEY  "), "surrounding spaces from a paste are ignored")
        assertTrue(service.hasKey())
    }

    @Test
    fun listsAreReadAtMostOnceADayAndForgottenWithTheKey() = runTest {
        assertNull(service.refresh(), "without a key there is nothing to do")
        assertTrue(tmdb.paths.isEmpty())

        service.setKey(GOOD_KEY)
        assertNull(service.refresh())
        assertEquals(6, library.listCounts().size, "every list")
        val requests = tmdb.paths.size

        now += 2.hours
        assertNull(service.refresh())
        assertEquals(requests, tmdb.paths.size, "fresh lists are not read again")
        assertNull(service.refresh(force = true))
        assertTrue(tmdb.paths.size > requests)

        service.removeKey()
        assertFalse(service.hasKey())
        assertTrue(library.listCounts().isEmpty())
    }

    private companion object {
        const val GOOD_KEY = "tmdb-canary-key-5c1e2f0a9b8d7c6e"
    }
}
