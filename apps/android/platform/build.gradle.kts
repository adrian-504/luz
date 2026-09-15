plugins {
    id("iptv.android-library")
}

android {
    namespace = "app.iptvplayer.platform"

    testOptions {
        unitTests.all { it.systemProperty("fixtures.dir", rootProject.file("tooling/fixtures").absolutePath) }
    }
}

dependencies {
    api(project(":shared:domain"))
    api(project(":shared:protocols"))
    api(project(":shared:ingestion"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    api(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(project(":apps:android:testing"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.sqlite.bundled)
}
