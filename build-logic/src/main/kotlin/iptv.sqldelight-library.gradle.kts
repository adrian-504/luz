// A shared KMP library with SQLDelight code generation (ADR-0013). Applying SQLDelight from build-logic keeps one
// Kotlin Gradle plugin instance for the whole build.
plugins {
    id("iptv.kmp-library")
    id("app.cash.sqldelight")
}
