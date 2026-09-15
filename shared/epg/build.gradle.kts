plugins {
    id("iptv.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
        }
    }
}
