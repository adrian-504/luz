pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "iptv-player"

include(":shared:domain")
include(":shared:protocols")
include(":shared:epg")
include(":shared:storage")

// Android apps need the Android SDK (on this machine: the external SSD). "auto" includes them only when an SDK is
// found through local.properties `sdk.dir` or ANDROID_HOME, so the shared core still builds without it.
val androidAppsSetting = providers.gradleProperty("iptv.androidApps").getOrElse("auto")
val androidSdkFound: Boolean = run {
    val localProperties = file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        java.util.Properties().apply { localProperties.inputStream().use { load(it) } }.getProperty("sdk.dir")
    } else {
        null
    }
    val candidate = fromLocal ?: providers.environmentVariable("ANDROID_HOME").orNull
    candidate != null && file(candidate).resolve("platforms").isDirectory
}
if (androidAppsSetting == "true" || (androidAppsSetting == "auto" && androidSdkFound)) {
    include(":apps:android:platform")
    include(":apps:android:testing")
    include(":apps:android:tv")
}
