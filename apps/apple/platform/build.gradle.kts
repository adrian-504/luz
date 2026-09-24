plugins {
    id("iptv.apple-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
