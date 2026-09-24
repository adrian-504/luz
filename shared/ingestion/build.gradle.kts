import iptv.EmbedFixtureBytes
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("iptv.kmp-library")
}

val embedFixtures = tasks.register<EmbedFixtureBytes>("embedFixtures") {
    val fixtures = layout.projectDirectory.dir("../../tooling/fixtures")
    this.fixtures.putAll(
        mapOf(
            "authSuccessJson" to fixtures.file("xtream/auth-success.json").asFile,
            "authFailureJson" to fixtures.file("xtream/auth-failure.json").asFile,
            "liveCategoriesJson" to fixtures.file("xtream/live-categories.json").asFile,
            "liveStreamsJson" to fixtures.file("xtream/live-streams.json").asFile,
            "smallValidM3u" to fixtures.file("m3u/small-valid.m3u").asFile,
            "smallValidXmltv" to fixtures.file("xmltv/small-valid.xml").asFile,
            "htmlErrorBody" to fixtures.file("xtream/html-error-body.html").asFile,
            "shortEpgJson" to fixtures.file("xtream/short-epg.json").asFile,
            "vodCategoriesJson" to fixtures.file("xtream/partial-failure/vod-categories.json").asFile,
            "vodStreamsJson" to fixtures.file("xtream/vod-streams.json").asFile,
            "seriesCategoriesJson" to fixtures.file("xtream/partial-failure/series-categories.json").asFile,
            "seriesJson" to fixtures.file("xtream/series.json").asFile,
            "seriesInfoJson" to fixtures.file("xtream/series-info.json").asFile,
            "vodInfoJson" to fixtures.file("xtream/vod-info.json").asFile,
        ),
    )
    packageName.set("app.iptvplayer.ingestion.fixtures")
    objectName.set("Fixtures")
    outputDir.set(layout.buildDirectory.dir("generated/fixtureBytes/commonTest/kotlin"))
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            api(project(":shared:protocols"))
            api(project(":shared:storage"))
            implementation(project(":shared:epg"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest {
            kotlin.srcDir(embedFixtures)
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// Storage on Apple uses the system SQLite (ADR-0013): every binary that contains it links libsqlite3. The storage module
// gets this from the SQLDelight plugin; this module does not apply that plugin, so its test binaries ask for it here.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    binaries.all { linkerOpts("-lsqlite3") }
}
