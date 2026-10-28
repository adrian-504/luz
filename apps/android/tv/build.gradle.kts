plugins {
    id("iptv.android-tv-app")
}

android {
    namespace = "app.iptvplayer.tv"

    // The About screen shows the version the viewer is running.
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "app.iptvplayer.tv"
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        // The release build as R8 leaves it, plus the debug build's test provider, for the robot in apps/android/benchmark to
        // drive from outside (ADR-0049). Never shipped.
        create("shrunk") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        // The same without R8, for recording the baseline profile: a profile names classes and methods as the source does,
        // and R8 merges and rewrites them (ADR-0049). Never shipped.
        create("profiling") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets.getByName("shrunk").kotlin.directories.add("src/debug/kotlin")
    sourceSets.getByName("profiling").kotlin.directories.add("src/debug/kotlin")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(project(":apps:android:platform"))
    implementation(project(":shared:epg"))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.qrcodegen)
    debugImplementation(project(":apps:android:testing"))
    "shrunkImplementation"(project(":apps:android:testing"))
    "shrunkImplementation"(libs.compose.ui.test.manifest)
    "profilingImplementation"(project(":apps:android:testing"))
    "profilingImplementation"(libs.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
