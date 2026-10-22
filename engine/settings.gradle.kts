pluginManagement {
    repositories {
        google()
        // Maven Central 常回 429，先走 Google 的鏡像
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        // Maven Central 常回 429，先走 Google 的鏡像
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "caocao"
include(":core")
