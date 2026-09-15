package app.iptvplayer.protocols.m3u

import app.iptvplayer.domain.capability.Support
import app.iptvplayer.domain.error.ValidationFailure
import app.iptvplayer.domain.id.PlaylistId
import app.iptvplayer.domain.model.CatchUpMode
import app.iptvplayer.domain.model.ContentKind
import app.iptvplayer.domain.model.DrmScheme
import app.iptvplayer.domain.model.MediaLocator
import app.iptvplayer.domain.model.StreamProtocol
import app.iptvplayer.domain.ports.ByteArraySource
import app.iptvplayer.domain.ports.ParseOutcome
import app.iptvplayer.domain.security.Secret
import app.iptvplayer.domain.security.SensitiveUrl
import app.iptvplayer.domain.security.UrlTemplate
import app.iptvplayer.protocols.content.ContentItem
import app.iptvplayer.protocols.content.SourceCredentials
import app.iptvplayer.protocols.fixtures.Fixtures
import app.iptvplayer.protocols.sniff.SniffedFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uImporterTest {
    private val playlist = PlaylistId("8f14e45f-ceea-467a-9575-6f3b4a1d2c01")
    private val canaryUser = "canary-user"
    private val canaryPass = "CANARY-PW-7f3a9c-DO-NOT-LOG"

    private class Imported(val result: M3uImportResult, val items: List<ContentItem>) {
        val channels get() = items.filterIsInstance<ContentItem.ChannelItem>()
        val movies get() = items.filterIsInstance<ContentItem.MovieItem>()
        val groups get() = items.filterIsInstance<ContentItem.Group>().map { it.group }
        fun channel(name: String) = channels.single { it.channel.name == name }
    }

    private fun import(bytes: ByteArray, credentials: SourceCredentials? = null): Imported {
        val items = ArrayList<ContentItem>()
        val result = M3uImporter.import(ByteArraySource(bytes), playlist, credentials) { items.add(it) }
        return Imported(result, items)
    }

    private fun import(text: String) = import(text.encodeToByteArray())

    @Test
    fun smallValidMatchesManifestExpectations() {
        val imported = import(Fixtures.smallValidM3u)
        assertEquals(ParseOutcome.Completed, imported.result.outcome)
        assertEquals(5, imported.result.counts.entries)
        assertEquals(4, imported.channels.size, "LIVE")
        assertEquals(1, imported.movies.size, "MOVIE")
        assertEquals(listOf("News", "Sports", "Kids", "Movies"), imported.groups.map { it.title })
        assertEquals(ContentKind.MOVIE, imported.groups.last().contentKind)
        assertEquals(
            listOf("https://epg.example.org/small-valid.xml"),
            imported.items.filterIsInstance<ContentItem.EpgHint>().map {
                it.url.template
            },
        )
        assertEquals(0, imported.result.diagnostics.size, imported.result.diagnostics.toString())

        val sports = imported.channel("Demo Sports 1")
        assertEquals(10, sports.channel.number)
        assertEquals(StreamProtocol.HLS, sports.mediaSource.protocolHint)
        assertEquals(StreamProtocol.PROGRESSIVE_TS, imported.channel("Example Kids").mediaSource.protocolHint)
        val replay = assertNotNull(imported.channel("Example Replay").channel.catchUp)
        assertEquals(CatchUpMode.M3U_DEFAULT, replay.mode)
        assertEquals(2, replay.days)
        assertEquals(2025, imported.movies.single().movie.year)
        assertEquals(StreamProtocol.PROGRESSIVE_MP4, imported.movies.single().mediaSource.protocolHint)
    }

    @Test
    fun unusualAttributesMatchManifestExpectations() {
        val imported = import(Fixtures.unusualAttributesM3u, SourceCredentials(Secret(canaryUser), Secret(canaryPass)))
        assertEquals(9, imported.result.counts.entries)
        assertEquals(0, imported.result.counts.rejectedEntries, imported.result.diagnostics.toString())
        assertNotNull(imported.channels.firstOrNull { it.channel.name == "Demo Sports 1, Live" })
        assertNotNull(imported.channels.firstOrNull { it.channel.name == "Ünïcødé Kanal — 東京 テスト" })

        val kids = imported.channel("Example Kids")
        assertEquals(
            "https://img.example.com/logo/kids%20channel.png",
            kids.logo?.url?.template,
            "URL-encoded logo decoded once, space re-encoded",
        )
        assertEquals(listOf("Kids", "Family"), kids.channel.groupIds.map { id -> imported.groups.single { it.id == id }.title })
        assertEquals(42, kids.channel.number)
        assertEquals(listOf("English", "Spanish"), kids.channel.languages)
        assertEquals("ExampleUA/2.0", kids.mediaSource.headers.userAgent)
        assertEquals("https://www.example.com/", kids.mediaSource.headers.referrer)

        val docs = imported.channel("Example Docs")
        assertEquals(listOf("Documentary"), docs.channel.groupIds.map { id -> imported.groups.single { it.id == id }.title })
        assertEquals(mapOf("x-custom-flag" to "yes", "provider-quality" to "fhd"), docs.channel.extras)

        val sports = imported.channel("Demo Sports 1, Live")
        assertEquals("ExamplePlayer/1.0", sports.mediaSource.headers.userAgent)
        assertEquals("https://www.example.com/", sports.mediaSource.headers.referrer)

        val premium = imported.channel("Example Premium")
        assertEquals(DrmScheme.WIDEVINE, premium.mediaSource.drm?.scheme)
        assertEquals("https://license.example.com/widevine", premium.mediaSource.drm?.licenseUrl?.template)
        assertEquals(StreamProtocol.DASH, premium.mediaSource.protocolHint)
        assertTrue(premium.mediaSource.headers.custom.isEmpty())
        val cookieRef = assertNotNull(premium.mediaSource.headers.sensitive["Cookie"])
        val secretHeader = imported.items.filterIsInstance<ContentItem.SensitiveHeader>().single()
        assertEquals(cookieRef, secretHeader.ref)
        assertEquals("session=canary-cookie-value", secretHeader.value.unsafeValue())

        val replay = assertNotNull(imported.channel("Example Replay").channel.catchUp)
        assertEquals(CatchUpMode.M3U_APPEND, replay.mode)
        assertEquals(7, replay.days)
        assertEquals("?utc={utc}&lutc={lutc}", replay.template?.template)
        assertEquals(CatchUpMode.M3U_SHIFT, imported.channel("Example Docs").channel.catchUp?.mode, "header catch-up default applies")

        assertEquals("Example Film (2024)", imported.movies.single().movie.title)
        assertEquals(StreamProtocol.MATROSKA, imported.movies.single().mediaSource.protocolHint)
        assertEquals(StreamProtocol.UNKNOWN, imported.channel("Example Radio").mediaSource.protocolHint)

        val hints = imported.items.filterIsInstance<ContentItem.EpgHint>()
        assertEquals(listOf("https://epg.example.org/guide.xml.gz", "https://epg.example.org/alt.xml"), hints.map { it.url.template })
        assertEquals(60, hints.first().timeShiftMinutes)
    }

    @Test
    fun credentialsNeverSurviveNormalization() {
        val imported = import(Fixtures.unusualAttributesM3u, SourceCredentials(Secret(canaryUser), Secret(canaryPass)))
        val news = imported.channel("Example News HD")
        val locator = news.mediaSource.locator as MediaLocator.DirectUrl
        assertEquals("http://provider.example.com/live/${UrlTemplate.USERNAME}/${UrlTemplate.PASSWORD}/1001.ts", locator.template.template)
        assertEquals(
            "http://provider.example.com/live/$canaryUser/$canaryPass/1001.ts",
            locator.template.expand(Secret(canaryUser), Secret(canaryPass))?.unsafeRawValue(),
        )
        // Every emitted item, printed or not, is free of the canary password (the cookie secret is held in Secret).
        for (item in imported.items) {
            assertFalse(item.toString().contains(canaryPass), item.toString())
            val raw = when (item) {
                is ContentItem.ChannelItem -> (item.mediaSource.locator as? MediaLocator.DirectUrl)?.template?.template
                is ContentItem.MovieItem -> (item.mediaSource.locator as? MediaLocator.DirectUrl)?.template?.template
                else -> null
            }
            if (raw != null) assertFalse(raw.contains(canaryPass) || raw.contains(canaryUser), raw)
        }
        for (diagnostic in imported.result.diagnostics) assertFalse(diagnostic.toString().contains(canaryPass))
    }

    @Test
    fun malformedFixtureMatchesManifestExpectations() {
        val imported = import(Fixtures.malformedM3u)
        assertEquals(ParseOutcome.Completed, imported.result.outcome)
        val names = imported.channels.map { it.channel.name }
        assertTrue(names.containsAll(listOf("Valid Channel One", "Valid Channel Two", "Duplicate Channel")), names.toString())
        assertEquals(1, names.count { it == "Duplicate Channel" })
        assertEquals(1, imported.result.counts.collapsedDuplicates)
        assertFalse(imported.channels.any { it.channel.name.contains("Scheme") }, "javascript: and file: entries rejected")
        assertEquals(2, imported.result.diagnosticCounts[M3uDiagnosticCodes.UNSUPPORTED_SCHEME])
        for (code in listOf(
            M3uDiagnosticCodes.EXTINF_WITHOUT_URL,
            M3uDiagnosticCodes.UNSUPPORTED_SCHEME,
            M3uDiagnosticCodes.INVALID_UTF8,
            M3uDiagnosticCodes.LINE_TOO_LONG,
        )) {
            assertTrue(code in imported.result.diagnosticCounts, "missing $code")
        }
        assertTrue("bare-url-without-extinf" in names, "bare URL named from its path")
        assertTrue("notitle" in names, "missing title falls back to URL stem")
        assertTrue(names.any { it.startsWith("Invalid ") && it.contains(Char(0xFFFD)) })
    }

    @Test
    fun rejectedBodiesReportValidationFailures() {
        assertEquals(ParseOutcome.Rejected(ValidationFailure.SOURCE_IS_HLS_PLAYLIST), import(Fixtures.hlsDisguisedM3u).result.outcome)
        assertEquals(ParseOutcome.Rejected(ValidationFailure.UNEXPECTED_CONTENT_TYPE_HTML), import(Fixtures.htmlErrorBody).result.outcome)
        assertEquals(ParseOutcome.Rejected(ValidationFailure.UNRECOGNIZED_FORMAT), import(Fixtures.authSuccessJson).result.outcome)
        assertEquals(ParseOutcome.Rejected(ValidationFailure.UNRECOGNIZED_FORMAT), import(Fixtures.smallValidXmltvGz).result.outcome)
        assertEquals(ParseOutcome.Rejected(ValidationFailure.EMPTY_RESPONSE), import("").result.outcome)
        assertEquals(ParseOutcome.Rejected(ValidationFailure.UNRECOGNIZED_FORMAT), import("just some text\nwithout urls\n").result.outcome)
        val bareList = import("http://cdn.example.net/one.ts\nhttp://cdn.example.net/two.ts\n")
        assertEquals(SniffedFormat.UNKNOWN, bareList.result.sniffedFormat)
        assertEquals(2, bareList.channels.size)
    }

    @Test
    fun idsAreStableAcrossImportsAndGroupRenames() {
        val first = import(Fixtures.smallValidM3u).channels.map { it.channel.id }
        assertEquals(first, import(Fixtures.smallValidM3u).channels.map { it.channel.id })
        val renamed = import(
            Fixtures.smallValidM3u.decodeToString().replace("group-title=\"News\"", "group-title=\"World News\""),
        ).channels.map {
            it.channel.id
        }
        assertEquals(first, renamed, "group is not part of the channel key (ADR-0017)")
        assertNotEquals(
            first.first(),
            import(Fixtures.smallValidM3u.decodeToString().replace(",Example News\n", ",Example News 2\n")).channels.first().channel.id,
        )
    }

    @Test
    fun sameNameDifferentUrlsGetCollisionOrdinalsAndCredentialChangesKeepIds() {
        val body =
            listOf(
                "#EXTM3U",
                "#EXTINF:-1,Same",
                "http://cdn.example.net/a.ts",
                "#EXTINF:-1,Same",
                "http://cdn.example.net/b.ts",
                "#EXTINF:-1 group-title=\"Other\",Same",
                "http://cdn.example.net/a.ts",
            ).joinToString("\n", postfix = "\n")
        val imported = import(body)
        assertEquals(2, imported.channels.size)
        assertEquals(1, imported.result.counts.collapsedDuplicates)
        assertEquals(1, imported.items.filterIsInstance<ContentItem.GroupMembership>().size)

        val withCredentials = "#EXTM3U\n#EXTINF:-1,Xtream\nhttp://provider.example.com/live/USER/PASS/7.ts\n"
        val a =
            import(
                withCredentials.replace("USER", "canary-user").replace("PASS", "CANARY-PW-old").encodeToByteArray(),
                SourceCredentials(Secret("canary-user"), Secret("CANARY-PW-old")),
            )
        val b =
            import(
                withCredentials.replace("USER", "canary-user").replace("PASS", "CANARY-PW-new").encodeToByteArray(),
                SourceCredentials(Secret("canary-user"), Secret("CANARY-PW-new")),
            )
        assertEquals(a.channels.single().channel.id, b.channels.single().channel.id)
        assertEquals(
            a.channels.single().mediaSource.id,
            b.channels.single().mediaSource.id,
            "templating makes media source identity independent of the password",
        )
    }

    @Test
    fun classificationAndSeries() {
        val body = """
            #EXTM3U
            #EXTINF:-1 group-title="Series | Drama",Example Show S01E02
            http://cdn.example.net/vod/show-s01e02.mkv
            #EXTINF:-1 group-title="Series | Drama",Example Show S01 E03
            http://cdn.example.net/vod/show-s01e03.mkv
            #EXTINF:-1 group-title="Series | Drama",Example Show S02E01
            http://cdn.example.net/vod/show-s02e01.mkv
            #EXTINF:-1 group-title="Films",Example Picture (1999)
            http://cdn.example.net/content/picture.mp4
            #EXTINF:-1 tvg-type="series",No Episode Number
            http://cdn.example.net/x.mkv
            #EXTINF:-1 group-title="Movies",Live Override
            http://provider.example.com/live/canary-user/CANARY-PW-7f3a9c-DO-NOT-LOG/9.ts
        """.trimIndent()
        val imported = import(body)
        assertEquals(1, imported.result.counts.series)
        assertEquals(2, imported.result.counts.seasons)
        assertEquals(listOf(2, 3, 1), imported.items.filterIsInstance<ContentItem.EpisodeItem>().map { it.episode.episodeNumber })
        assertEquals("Example Show", imported.items.filterIsInstance<ContentItem.SeriesItem>().single().series.title)
        assertEquals(setOf("Example Picture (1999)", "No Episode Number"), imported.movies.map { it.movie.title }.toSet())
        assertEquals(1999, imported.movies.single { it.movie.title.startsWith("Example") }.movie.year)
        assertTrue(M3uDiagnosticCodes.SERIES_WITHOUT_EPISODE in imported.result.diagnosticCounts)
        assertEquals(listOf("Live Override"), imported.channels.map { it.channel.name }, "/live/ URL path wins over group keywords")
    }

    @Test
    fun xtreamGeneratedPlaylistUrlsAreDetected() {
        val url = "http://provider.example.com:8080/get.php?" +
            "username=canary-user&password=CANARY-PW-7f3a9c-DO-NOT-LOG&type=m3u_plus&output=ts"
        val candidate = assertNotNull(XtreamM3uDetector.detect(SensitiveUrl.of(url)))
        assertEquals("http://provider.example.com:8080", candidate.baseUrl)
        assertEquals("canary-user", candidate.username.unsafeValue())
        assertEquals("CANARY-PW-7f3a9c-DO-NOT-LOG", candidate.password.unsafeValue())
        assertEquals("ts", candidate.outputFormat)
        assertFalse(candidate.toString().contains("CANARY"))
        assertNull(XtreamM3uDetector.detect(SensitiveUrl.of("http://provider.example.com/get.php?username=canary-user")))
        assertNull(XtreamM3uDetector.detect(SensitiveUrl.of("https://cdn.example.net/playlist.m3u")))
    }

    @Test
    fun capabilityDefaultsAreUntouched() {
        // Import never claims provider capabilities; discovery does (ADR-0008 recording stays unsupported).
        assertEquals(Support.UNSUPPORTED, app.iptvplayer.domain.capability.Capabilities.UNDISCOVERED.recording)
    }
}
