// Convention for shared Kotlin Multiplatform library modules (ADR-0011, ADR-0012, docs/TESTING.md §6).

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// Apple Kotlin/Native targets need a full Xcode installation. "auto" enables them only when the iOS simulator SDK
// is resolvable, so the build works on machines with Command Line Tools only.
val appleTargetsEnabled: Boolean = when (providers.gradleProperty("iptv.appleTargets").getOrElse("auto")) {
    "true" -> true
    "false" -> false
    else -> runCatching {
        providers.exec {
            commandLine("xcrun", "--sdk", "iphonesimulator", "--show-sdk-path")
            isIgnoreExitValue = true
        }.result.get().exitValue == 0
    }.getOrDefault(false)
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm()
    if (appleTargetsEnabled) {
        iosArm64()
        iosSimulatorArm64()
        tvosArm64()
        tvosSimulatorArm64()
    }

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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
