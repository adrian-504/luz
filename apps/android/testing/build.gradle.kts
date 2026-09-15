// Test-only support: an in-process HTTP server serving the synthetic media in tooling/fixtures/media with injectable
// faults. Used by device tests and by the TV app's debug build; never part of a release build.
plugins {
    id("iptv.android-library")
}

android {
    namespace = "app.iptvplayer.testing"

    sourceSets {
        getByName("main") {
            assets.directories.add(rootProject.file("tooling/fixtures/media").absolutePath)
        }
    }
}
