// Convention for shared Kotlin Multiplatform library modules (ADR-0011, ADR-0012, docs/TESTING.md §6).

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

// The Android target needs the Android SDK; same rule as the app in settings.gradle.kts (iptv.androidApps).
val androidTargetEnabled: Boolean = run {
    val setting = providers.gradleProperty("iptv.androidApps").getOrElse("auto")
    val localProperties = rootProject.file("local.properties")
    val sdkDir = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else {
        null
    } ?: providers.environmentVariable("ANDROID_HOME").orNull
    setting == "true" || (setting == "auto" && sdkDir != null && file(sdkDir).resolve("platforms").isDirectory)
}
if (androidTargetEnabled) {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
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
    if (androidTargetEnabled) {
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            namespace = "app.iptvplayer.shared." + project.name
            compileSdk = 37
            minSdk = 26
            compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            // commonTest also runs on an Android device or emulator: IDs and text normalization must match the JVM.
            withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
        }
    }
    // jvmMain and androidMain share the java.* based actuals (java.text.Normalizer, java.util.zip). The hierarchy
    // builder is still marked experimental in KGP 2.4; the fallback is one copy of each actual per target.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withCompilations { it.target is KotlinMultiplatformAndroidLibraryTarget }
            }
        }
    }
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
        findByName("androidDeviceTest")?.dependencies {
            implementation(versionCatalogs.named("libs").findLibrary("androidx-test-runner").get())
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// A module whose tests are all JVM-only (storage and ingestion: they need a SQLite driver, which Apple gets in Phase 10)
// has no tests to run on an Apple simulator. That is reported as "no tests", not as a failure; the modules that do have
// common tests still fail on any failing one. Remove this once every module has Apple tests.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    failOnNoDiscoveredTests.set(false)
}

// Apple TV comes after the iPhone (ROADMAP, 2026-09-21), and its simulator is a separate multi-gigabyte download not
// installed yet. Without it the tvOS tests are switched off rather than failing; the tvOS code still compiles on every
// run, and the tests run by themselves once `xcodebuild -downloadPlatform tvOS` has been done. Decided while the build is
// configured: a condition checked later would have to hold a reference to this script, which the configuration cache
// cannot store.
if (appleTargetsEnabled) {
    val tvosRuntimeInstalled = providers.exec { commandLine("xcrun", "simctl", "list", "runtimes") }
        .standardOutput.asText.map { it.contains("tvOS") }.getOrElse(false)
    tasks.matching { it.name == "tvosSimulatorArm64Test" }.configureEach {
        enabled = tvosRuntimeInstalled
    }
}
