// Convention for the Android TV application (ADR-0012, ADR-0023). AGP 9 compiles Kotlin itself (built-in Kotlin); the
// Compose compiler plugin comes from the same Kotlin version as the shared core.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // The performance targets of docs/PERFORMANCE.md apply to the release build, so it has to be installable to be
        // measured. It is signed with the local debug key — generated on this machine, never committed — until release
        // hardening (Phase 15) provides a real one. Code shrinking stays off until it is enabled deliberately, with the
        // rules the reflective libraries need and a device-test run against the shrunk build.
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
    }
}

extensions.configure<KotlinAndroidProjectExtension> {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }
}
