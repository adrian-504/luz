// Convention for Apple-only Kotlin/Native modules: the platform adapters the shared core needs on iOS and tvOS
// (Keychain, URLSession, playback), the counterpart of apps/android/platform (ADR-0011, docs/PLATFORM_STRATEGY.md).

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    explicitApi()

    iosArm64()
    iosSimulatorArm64()
    tvosArm64()
    tvosSimulatorArm64()

    compilerOptions {
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Same rule as the shared modules: tvOS simulator tests wait for a tvOS runtime (iptv.kmp-library.gradle.kts). Decided
// while the build is configured, because a condition checked later would hold a reference to this script, which the
// configuration cache cannot store.
val tvosRuntimeInstalled = providers.exec { commandLine("xcrun", "simctl", "list", "runtimes") }
    .standardOutput.asText.map { it.contains("tvOS") }.getOrElse(false)
tasks.matching { it.name == "tvosSimulatorArm64Test" }.configureEach {
    enabled = tvosRuntimeInstalled
}
