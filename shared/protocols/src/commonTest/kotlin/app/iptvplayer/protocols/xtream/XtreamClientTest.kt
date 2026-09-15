package app.iptvplayer.protocols.xtream

import app.iptvplayer.domain.capability.StreamFormat
import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.error.AuthFailure
import app.iptvplayer.domain.error.DomainError
import app.iptvplayer.domain.error.NetworkErrorKind
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.id.EpgChannelKey
import app.iptvplayer.domain.id.EpgSourceId
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.CatchUpMode
import app.iptvplayer.domain.model.ImportStatus
import app.iptvplayer.domain.model.ImportUnit
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.model.XtreamStreamKind
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.fixtures.Fixtures
import app.iptvplayer.protocols.net.HttpFetcher
import app.iptvplayer.protocols.testing.FakeTransport
import app.iptvplayer.protocols.testing.FakeTransport.Reply
import app.iptvplayer.protocols.testing.FixedClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class XtreamClientTest {
    private val user = "canary-user"
    private val pass = "CANARY-PW-7f3a9c-DO-NOT-LOG"
    private val credentials = XtreamCredentials(Secret(user), Secret(pass))
    private val endpoint = assertNotNull(XtreamEndpoint.parse("http://provider.example.com"))
    private val playlist = PlaylistId("8f14e45f-ceea-467a-9575-6f3b4a1d2c01")
    private val now = Instant.fromEpochSeconds(1_789_387_200)

    private val defaultRoutes: Map<String?, Reply> = mapOf(
        null to FakeTransport.ok(Fixtures.authSuccessJson),
        "get_live_categories" to FakeTransport.ok(Fixtures.liveCategoriesJson),
        "get_live_streams" to FakeTransport.ok(Fixtures.liveStreamsJson),
        "get_vod_categories" to FakeTransport.ok(Fixtures.partialVodCategoriesJson),
        "get_vod_streams" to FakeTransport.ok(Fixtures.vodStreamsJson),
        "get_series_categories" to FakeTransport.ok(Fixtures.partialSeriesCategoriesJson),
        "get_series" to FakeTransport.ok(Fixtures.seriesJson),
        "get_series_info" to FakeTransport.ok(Fixtures.seriesInfoJson),
        "get_short_epg" to FakeTransport.ok(Fixtures.shortEpgJson),
    )

    private fun client(overrides: Map<String?, Reply> = emptyMap()): Pair<XtreamClient, FakeTransport> {
        val routes = defaultRoutes + overrides
        val transport = FakeTransport { request ->
            routes[FakeTransport.param(request.url, "action")] ?: FakeTransport.status(404)
        }
        return XtreamClient(HttpFetcher(transport, jitter = { 0.0 }, sleep = {}), FixedClock(now)) to transport
    }

    private class Collected(val result: XtreamUnitResult, val items: List<ContentItem>)

    private suspend fun import(
        unit: ImportUnit,
        overrides: Map<String?, Reply> = emptyMap(),
        formats: Set<StreamFormat> = setOf(StreamFormat.HLS, StreamFormat.MPEG_TS),
    ): Collected {
        val (client, transport) = client(overrides)
        val items = ArrayList<ContentItem>()
        val account = app.iptvplayer.domain.model.ProviderAccount(
            app.iptvplayer.domain.model.AccountStatus.ACTIVE,
            null,
            null,
            1,
            0,
            formats,
            null,
        )
        val result = client.importUnit(unit, endpoint, credentials, playlist, account) { items += it }
        assertEquals(0, transport.openBodies, "every response body closed")
        return Collected(result, items)
    }

    @Test
    fun discoverySucceedsWithAccountCapabilitiesAndEpgTemplate() = runTest {
        val (client, transport) = client()
        val discovery = assertIs<XtreamDiscovery.Authenticated>(client.discover(endpoint, credentials))
        assertEquals(1, discovery.account.maxConnections)
        assertEquals(Instant.fromEpochSeconds(1_798_761_600), discovery.account.expiresAt)
        assertEquals(setOf(StreamFormat.HLS, StreamFormat.MPEG_TS), discovery.account.allowedOutputFormats)
        assertEquals("Europe/London", discovery.account.serverTimezone)
        assertEquals(false, discovery.account.isTrial)
        assertEquals(Support.SUPPORTED, discovery.capabilities.liveTv)
        assertEquals(Support.SUPPORTED, discovery.capabilities.movies)
        assertEquals(Support.SUPPORTED, discovery.capabilities.series)
        assertEquals(Support.UNSUPPORTED, discovery.capabilities.recording)
        assertFalse(discovery.hostMismatch)
        assertTrue(discovery.httpsAvailable, "server_info reports https_port 443")
        assertEquals(
            "http://provider.example.com/xmltv.php?username=${UrlTemplate.USERNAME}&password=${UrlTemplate.PASSWORD}",
            discovery.epgTemplate.template,
        )
        assertTrue(XtreamDiagnosticCodes.CONNECTION_LIMIT_REACHED !in discovery.diagnostics.map { it.code })
        assertEquals(4, transport.requests.size, "auth + three category probes")
    }

    @Test
    fun discoveryClassifiesFailures() = runTest {
        suspend fun failure(reply: Reply): DomainError =
            assertIs<XtreamDiscovery.Failed>(client(mapOf(null to reply)).first.discover(endpoint, credentials)).error
        assertEquals(DomainError.Auth(AuthFailure.INVALID_CREDENTIALS), failure(FakeTransport.ok(Fixtures.authFailureJson)))
        assertEquals(DomainError.Auth(AuthFailure.ACCOUNT_EXPIRED), failure(FakeTransport.ok(Fixtures.authExpiredJson)))
        assertEquals(
            DomainError.Validation(ValidationFailure.UNEXPECTED_CONTENT_TYPE_HTML),
            failure(FakeTransport.ok(Fixtures.htmlErrorBody)),
        )
        assertEquals(DomainError.Validation(ValidationFailure.EMPTY_RESPONSE), failure(FakeTransport.ok("")))
        assertEquals(DomainError.Validation(ValidationFailure.UNRECOGNIZED_FORMAT), failure(FakeTransport.ok("""{"server_info":{}}""")))
        assertEquals(DomainError.Auth(AuthFailure.INVALID_CREDENTIALS), failure(FakeTransport.status(401)))
        assertEquals(DomainError.Network(NetworkErrorKind.DNS), failure(Reply.Fail(NetworkErrorKind.DNS)))
        assertEquals(DomainError.Parse("XTREAM_INVALID_JSON"), failure(FakeTransport.ok("""{"user_info":{"auth":1""")))
        val banned = Fixtures.authSuccessJson.decodeToString().replace("\"Active\"", "\"Banned\"")
        assertEquals(DomainError.Auth(AuthFailure.ACCOUNT_BANNED), failure(FakeTransport.ok(banned)))
    }

    @Test
    fun serverHostMismatchIsSurfacedNeverAdopted() = runTest {
        val body = Fixtures.authSuccessJson.decodeToString().replace("\"url\": \"provider.example.com\"", "\"url\": \"other.example.net\"")
        val (client, transport) = client(mapOf(null to FakeTransport.ok(body)))
        val discovery = assertIs<XtreamDiscovery.Authenticated>(client.discover(endpoint, credentials))
        assertTrue(discovery.hostMismatch)
        assertEquals("other.example.net", discovery.reportedHost)
        assertTrue(XtreamDiagnosticCodes.SERVER_HOST_MISMATCH in discovery.diagnostics.map { it.code })
        assertTrue(transport.requests.all { it.url.unsafeRawValue().startsWith("http://provider.example.com/") })
    }

    @Test
    fun liveUnitMatchesManifestExpectations() = runTest {
        val collected = import(ImportUnit.LIVE)
        assertEquals(ImportStatus.PUBLISHED, collected.result.status)
        val groups = collected.items.filterIsInstance<ContentItem.Group>().map { it.group }
        assertEquals(3, groups.size)
        assertEquals("Kids & Family", groups[2].title)
        val channels = collected.items.filterIsInstance<ContentItem.ChannelItem>()
        assertEquals(3, channels.size)
        assertEquals(1, collected.result.counts.rejectedEntries, "stream 1004 has an empty name")
        val byId = channels.associateBy { it.channel.providerStreamId }
        assertEquals("news.example", byId["1001"]?.channel?.tvgId)
        assertNull(byId["1002"]?.channel?.tvgId)
        assertNull(byId["1003"]?.channel?.tvgId)
        assertEquals(CatchUpMode.XTREAM_TIMESHIFT, byId["1001"]?.channel?.catchUp?.mode)
        assertEquals(3, byId["1001"]?.channel?.catchUp?.days)
        assertNull(byId["1002"]?.channel?.catchUp)
        val kids = assertNotNull(byId["1003"]).channel
        assertEquals(listOf(groups[2].id, groups[1].id), kids.groupIds, "category_ids [\"3\", 2]")
        assertEquals(MediaLocator.XtreamStream(XtreamStreamKind.LIVE, "1001", null), byId["1001"]?.mediaSource?.locator)
        assertEquals(StreamProtocol.PROGRESSIVE_TS, byId["1001"]?.mediaSource?.protocolHint)
        assertTrue(XtreamDiagnosticCodes.NO_USABLE_NAME in collected.result.diagnosticCounts)
    }

    @Test
    fun uncoercibleValuesAreReportedByFieldNameAndTreatedAsAbsent() = runTest {
        val body = """[{"stream_id":7,"name":"Seven","num":"abc","tv_archive":"maybe","category_id":{"x":1}}]"""
        val collected = import(ImportUnit.LIVE, mapOf("get_live_streams" to FakeTransport.ok(body)))
        val channel = collected.items.filterIsInstance<ContentItem.ChannelItem>().single().channel
        assertNull(channel.number)
        assertNull(channel.catchUp)
        assertEquals(3, collected.result.diagnosticCounts[XtreamDiagnosticCodes.FIELD_TYPE_MISMATCH])
        assertTrue(collected.result.diagnostics.none { it.message.contains("abc") || it.message.contains("maybe") })
    }

    @Test
    fun liveProtocolHintFollowsAllowedFormats() = runTest {
        val hlsOnly = import(ImportUnit.LIVE, formats = setOf(StreamFormat.HLS))
        assertEquals(StreamProtocol.HLS, hlsOnly.items.filterIsInstance<ContentItem.ChannelItem>().first().mediaSource.protocolHint)
        val unknown = import(ImportUnit.LIVE, formats = emptySet())
        assertEquals(StreamProtocol.UNKNOWN, unknown.items.filterIsInstance<ContentItem.ChannelItem>().first().mediaSource.protocolHint)
    }

    @Test
    fun moviesAndSeriesUnits() = runTest {
        val movies = import(ImportUnit.MOVIES)
        assertEquals(ImportStatus.PUBLISHED, movies.result.status)
        val hints = movies.items.filterIsInstance<ContentItem.MovieItem>().associate {
            it.movie.providerStreamId to
                it.mediaSource.protocolHint
        }
        assertEquals(
            mapOf<String?, StreamProtocol>(
                "2001" to StreamProtocol.MATROSKA,
                "2002" to StreamProtocol.PROGRESSIVE_MP4,
                "2003" to StreamProtocol.UNKNOWN,
            ),
            hints,
        )
        val film = movies.items.filterIsInstance<ContentItem.MovieItem>().first { it.movie.providerStreamId == "2002" }.movie
        assertEquals(2024, film.year)
        assertEquals(Instant.fromEpochSeconds(1_789_065_000), film.addedAt)
        assertNull(
            movies.items.filterIsInstance<ContentItem.MovieItem>().first {
                it.movie.providerStreamId == "2002"
            }.movie.rating,
            "rating 0 means unrated",
        )

        val series = import(ImportUnit.SERIES)
        assertEquals(ImportStatus.PUBLISHED, series.result.status)
        val list = series.items.filterIsInstance<ContentItem.SeriesItem>().map { it.series }
        assertEquals(listOf("Example Series", "Another & Example"), list.map { it.title })
        assertEquals(listOf("Drama", "Mystery"), list[0].genres)
        assertEquals(2025, list[0].year)
        assertEquals(1, series.result.counts.rejectedEntries)
        assertTrue(XtreamDiagnosticCodes.MISSING_ID in series.result.diagnosticCounts)
        assertTrue(XtreamDiagnosticCodes.UNKNOWN_CATEGORY in series.result.diagnosticCounts)
    }

    @Test
    fun partialFailureScenario() = runTest {
        // tooling/fixtures/xtream/partial-failure/scenario.json
        val overrides: Map<String?, Reply> = mapOf(
            "get_vod_categories" to FakeTransport.ok(Fixtures.partialVodCategoriesJson),
            "get_vod_streams" to Reply.Body(500, Fixtures.partialServerErrorHtml),
            "get_series_categories" to FakeTransport.ok(Fixtures.partialSeriesCategoriesJson),
            "get_series" to FakeTransport.ok(Fixtures.partialTruncatedSeriesJson),
        )
        val (client, transport) = client(overrides)
        val emittedByUnit = HashMap<ImportUnit, Int>()
        val result = client.importAll(
            listOf(ImportUnit.LIVE, ImportUnit.MOVIES, ImportUnit.SERIES),
            endpoint,
            credentials,
            playlist,
            null,
        ) { unit, _ ->
            emittedByUnit[unit] = (emittedByUnit[unit] ?: 0) + 1
        }
        val status = result.units.associate { it.unit to it.status }
        assertEquals(
            mapOf(
                ImportUnit.LIVE to ImportStatus.PUBLISHED,
                ImportUnit.MOVIES to ImportStatus.FAILED,
                ImportUnit.SERIES to ImportStatus.FAILED,
            ),
            status,
        )
        assertEquals(ImportStatus.PARTIAL, result.playlistStatus)
        assertEquals(DomainError.Http(500), result.units[1].error)
        assertEquals(DomainError.Parse("XTREAM_INVALID_JSON"), result.units[2].error)
        assertEquals(0, transport.openBodies)
        assertEquals(
            2,
            transport.requests.count {
                FakeTransport.param(it.url, "action") == "get_vod_streams"
            },
            "HTTP 500 retried once (LARGE_LIST)",
        )
    }

    @Test
    fun expiredAccountMidImportFailsUnitWithAuthError() = runTest {
        val collected = import(ImportUnit.LIVE, mapOf("get_live_streams" to FakeTransport.ok(Fixtures.authFailureJson)))
        assertEquals(ImportStatus.FAILED, collected.result.status)
        assertEquals(DomainError.Auth(AuthFailure.INVALID_CREDENTIALS), collected.result.error)
        val html = import(ImportUnit.LIVE, mapOf("get_live_streams" to FakeTransport.ok(Fixtures.htmlErrorBody)))
        assertEquals(DomainError.Validation(ValidationFailure.UNEXPECTED_CONTENT_TYPE_HTML), html.result.error)
    }

    @Test
    fun seriesInfoBothPanelVariants() = runTest {
        val (client, _) = client()
        val (items, error) = client.seriesInfo(endpoint, credentials, playlist, "401", null)
        assertNull(error)
        val seasons = items.filterIsInstance<ContentItem.SeasonItem>().map { it.season }
        val episodes = items.filterIsInstance<ContentItem.EpisodeItem>().map { it.episode }
        assertEquals(listOf(1, 2), seasons.map { it.seasonNumber })
        assertEquals("Season 1", seasons[0].title)
        assertEquals(3, episodes.size)
        assertEquals(listOf(1, 2, 1), episodes.map { it.episodeNumber })
        assertEquals(2700L, episodes[0].duration?.inWholeSeconds)
        assertEquals(2640L, episodes[2].duration?.inWholeSeconds, "\"duration\": \"00:44:00\"")
        assertEquals(seasons[0].id, episodes[1].seasonId, "string season \"1\"")
        val locator = items.filterIsInstance<ContentItem.EpisodeItem>().first().mediaSource.locator
        assertEquals(MediaLocator.XtreamStream(XtreamStreamKind.SERIES, "30001", "mkv"), locator)

        val (arrayClient, _) = client(mapOf("get_series_info" to FakeTransport.ok(Fixtures.seriesInfoEpisodesArrayJson)))
        val (arrayItems, _) = arrayClient.seriesInfo(endpoint, credentials, playlist, "401", null)
        assertEquals(1, arrayItems.filterIsInstance<ContentItem.SeasonItem>().size)
        assertEquals(1, arrayItems.filterIsInstance<ContentItem.EpisodeItem>().size)
    }

    @Test
    fun shortEpgDecodesBase64AndUsesUtcTimestamps() = runTest {
        val (client, _) = client()
        val key = EpgChannelKey(EpgSourceId("xtream-short-epg"), "news.example")
        val (programmes, error) = client.shortEpg(endpoint, credentials, "1001", key)
        assertNull(error)
        assertEquals(3, programmes.size)
        assertEquals("Example Morning News", programmes[0].title)
        assertEquals(Instant.parse("2026-09-14T05:00:00Z"), programmes[0].start)
        assertEquals("Synthetic description for a fixture programme.", programmes[0].description)
        assertEquals("%%not-base64%%", programmes[2].title, "invalid base64 kept as received")
    }

    @Test
    fun mutatedResponsesNeverThrow() = runTest {
        val random = kotlin.random.Random(20260914)
        val seeds = listOf(Fixtures.liveStreamsJson, Fixtures.vodStreamsJson, Fixtures.seriesJson, Fixtures.liveCategoriesJson)
        repeat(150) { iteration ->
            val bytes = seeds[iteration % seeds.size].toMutableList()
            repeat(random.nextInt(1, 20)) {
                when (random.nextInt(3)) {
                    0 -> if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = "[]{}\",:0a".random(random).code.toByte()
                    1 -> if (bytes.isNotEmpty()) bytes.removeAt(random.nextInt(bytes.size))
                    else -> bytes.add(random.nextInt(bytes.size + 1), random.nextInt(256).toByte())
                }
            }
            val body = FakeTransport.ok(bytes.toByteArray())
            for (unit in listOf(ImportUnit.LIVE, ImportUnit.MOVIES, ImportUnit.SERIES)) {
                val overrides: Map<String?, Reply> = mapOf(
                    "get_live_streams" to body,
                    "get_vod_streams" to body,
                    "get_series" to body,
                    "get_live_categories" to body,
                )
                import(unit, overrides)
            }
        }
    }

    @Test
    fun credentialsNeverLeakIntoItemsErrorsOrDiagnostics() = runTest {
        val units = listOf(ImportUnit.LIVE, ImportUnit.MOVIES, ImportUnit.SERIES).map { import(it) }
        val (client, _) = client()
        val discovery = client.discover(endpoint, credentials)
        val texts = buildList {
            units.forEach { collected ->
                collected.items.forEach { add(it.toString()) }
                collected.result.diagnostics.forEach { add(it.toString()) }
                add(collected.result.error.toString())
            }
            add(discovery.toString())
            (discovery as XtreamDiscovery.Authenticated).diagnostics.forEach { add(it.toString()) }
            add(credentials.toString())
            add(endpoint.apiUrl(credentials).toString())
            add(endpoint.streamUrl(credentials, XtreamStreamKind.LIVE, "1", "ts").toString())
        }
        for (text in texts) {
            assertFalse(text.contains(pass) || text.contains(user), text)
        }
    }
}
