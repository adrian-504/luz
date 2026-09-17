import iptv.EmbedFixtureBytes

plugins {
    id("iptv.kmp-library")
}

val embedFixtures = tasks.register<EmbedFixtureBytes>("embedFixtures") {
    val fixtures = layout.projectDirectory.dir("../../tooling/fixtures")
    this.fixtures.putAll(
        mapOf(
            "smallValidM3u" to fixtures.file("m3u/small-valid.m3u").asFile,
            "unusualAttributesM3u" to fixtures.file("m3u/unusual-attributes.m3u").asFile,
            "malformedM3u" to fixtures.file("m3u/malformed.m3u").asFile,
            "hlsDisguisedM3u" to fixtures.file("m3u/hls-disguised.m3u").asFile,
            "liveMediaM3u8" to fixtures.file("hls/live-media.m3u8").asFile,
            "smallValidXmltv" to fixtures.file("xmltv/small-valid.xml").asFile,
            "smallValidXmltvGz" to fixtures.file("xmltv/small-valid.xml.gz").asFile,
            "timezoneVariantsXmltv" to fixtures.file("xmltv/timezone-variants.xml").asFile,
            "malformedXmltv" to fixtures.file("xmltv/malformed.xml").asFile,
            "xxeXmltv" to fixtures.file("xmltv/xxe-external-entity.xml").asFile,
            "entityExpansionXmltv" to fixtures.file("xmltv/entity-expansion.xml").asFile,
            "htmlErrorBody" to fixtures.file("xtream/html-error-body.html").asFile,
            "authSuccessJson" to fixtures.file("xtream/auth-success.json").asFile,
            "authFailureJson" to fixtures.file("xtream/auth-failure.json").asFile,
            "authExpiredJson" to fixtures.file("xtream/auth-expired.json").asFile,
            "liveCategoriesJson" to fixtures.file("xtream/live-categories.json").asFile,
            "liveStreamsJson" to fixtures.file("xtream/live-streams.json").asFile,
            "vodStreamsJson" to fixtures.file("xtream/vod-streams.json").asFile,
            "seriesJson" to fixtures.file("xtream/series.json").asFile,
            "seriesInfoJson" to fixtures.file("xtream/series-info.json").asFile,
            "vodInfoJson" to fixtures.file("xtream/vod-info.json").asFile,
            "tmdbTrendingMoviesJson" to fixtures.file("tmdb/trending-movies.json").asFile,
            "tmdbTopRatedTvJson" to fixtures.file("tmdb/top-rated-tv.json").asFile,
            "seriesInfoEpisodesArrayJson" to fixtures.file("xtream/series-info-episodes-array.json").asFile,
            "shortEpgJson" to fixtures.file("xtream/short-epg.json").asFile,
            "partialVodCategoriesJson" to fixtures.file("xtream/partial-failure/vod-categories.json").asFile,
            "partialSeriesCategoriesJson" to fixtures.file("xtream/partial-failure/series-categories.json").asFile,
            "partialServerErrorHtml" to fixtures.file("xtream/partial-failure/server-error.html").asFile,
            "partialTruncatedSeriesJson" to fixtures.file("xtream/partial-failure/truncated-series.json").asFile,
        ),
    )
    packageName.set("app.iptvplayer.protocols.fixtures")
    objectName.set("Fixtures")
    outputDir.set(layout.buildDirectory.dir("generated/fixtureBytes/commonTest/kotlin"))
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest {
            kotlin.srcDir(embedFixtures)
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}
