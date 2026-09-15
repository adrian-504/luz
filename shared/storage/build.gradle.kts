plugins {
    id("iptv.sqldelight-library")
}

sqldelight {
    databases {
        create("IptvDatabase") {
            packageName.set("app.iptvplayer.storage.db")
            dialect(libs.sqldelight.dialect.sqlite338)
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":shared:domain"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.serialization.json)
        }
        findByName("jvmCommonMain")?.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        findByName("appleMain")?.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}
