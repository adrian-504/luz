// Drives Luz from outside its own process with Macrobenchmark and UI Automator (ADR-0049): cold-start timing, a smoke run
// of the shrunk build, and the generator of Luz's baseline profile. It runs on the emulator or a device and is never part
// of the app. Run the smoke test and startup timing against the shrunk build,
// and the profile generator against the "profiling" build (no R8).

plugins {
    id("iptv.android-benchmark")
}

android {
    namespace = "app.iptvplayer.benchmark"

    defaultConfig {
        // Timings from the emulator are only a smoke check; the numbers that count come from the owner's TV.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    buildTypes {
        // Pairs with the app's "shrunk" build: R8's output plus the debug build's test provider.
        create("shrunk") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        // Pairs with the app's "profiling" build (no R8), which the baseline profile is recorded from.
        create("profiling") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    targetProjectPath = ":apps:android:tv"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "shrunk" || it.buildType == "profiling" }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
}
