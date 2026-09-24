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

// Same rule as the shared modules: tvOS simulator tests wait for a tvOS runtime (iptv.kmp-library.gradle.kts).
val simulatorRuntimes = providers.exec { commandLine("xcrun", "simctl", "list", "runtimes") }.standardOutput.asText
tasks.matching { it.name == "tvosSimulatorArm64Test" }.configureEach {
    onlyIf("a tvOS simulator runtime is installed (xcodebuild -downloadPlatform tvOS)") {
        simulatorRuntimes.getOrElse("").contains("tvOS")
    }
}
