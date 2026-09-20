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

    // A build meant for someone else's television is signed with the sharing key, which lives outside the repository and
    // is named on the command line (`-PluzKeystore=… -PluzKeystorePassword=…`). Without those, a release build is signed
    // with the local debug key, as it has been: it only has to be installable for the performance runs.
    val keystore = (findProperty("luzKeystore") as String?)?.let { file(it) }?.takeIf { it.exists() }
    if (keystore != null) {
        signingConfigs.create("sharing") {
            storeFile = keystore
            storePassword = findProperty("luzKeystorePassword") as String?
            keyAlias = (findProperty("luzKeyAlias") as String?) ?: "luz"
            keyPassword = (findProperty("luzKeyPassword") as String?) ?: (findProperty("luzKeystorePassword") as String?)
        }
    }

    buildTypes {
        // The performance targets of docs/PERFORMANCE.md apply to the release build, so it has to be installable to be
        // measured. Code shrinking stays off until it is enabled deliberately, with the rules the reflective libraries
        // need and a device-test run against the shrunk build.
        getByName("release") {
            signingConfig = if (keystore != null) signingConfigs.getByName("sharing") else signingConfigs.getByName("debug")
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
