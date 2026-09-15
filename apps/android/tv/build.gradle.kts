plugins {
    id("iptv.android-tv-app")
}

android {
    namespace = "app.iptvplayer.tv"

    defaultConfig {
        applicationId = "app.iptvplayer.tv"
        versionCode = 1
        versionName = "0.1.0-shell"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(project(":apps:android:platform"))
    implementation(project(":shared:epg"))
    debugImplementation(project(":apps:android:testing"))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
