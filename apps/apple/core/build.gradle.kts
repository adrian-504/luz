import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    id("iptv.apple-library")
}

// LuzCore.xcframework: the shared engine as the iPhone app sees it — one small Swift-facing API (LuzCore) over the
// import pipeline, storage, Keychain and URLSession (Phase 10 step 2). Only this module's public API is exported.
val luzCore = XCFramework("LuzCore")

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LuzCore"
            isStatic = true
            // The system SQLite behind storage on Apple (ADR-0013).
            linkerOpts("-lsqlite3")
            // The platform adapters too, so the app's own tests can check the Keychain, which exists only inside an app.
            export(project(":apps:apple:platform"))
            luzCore.add(this)
        }
    }
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.all { linkerOpts("-lsqlite3") }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:domain"))
            implementation(project(":shared:protocols"))
            implementation(project(":shared:storage"))
            implementation(project(":shared:ingestion"))
            api(project(":apps:apple:platform"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
