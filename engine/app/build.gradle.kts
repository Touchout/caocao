import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ccz.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ccz.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 44
        versionName = "0.4.4"
    }

    // 固定的 debug 簽章：雲端 VM 每次都會產生新的 ~/.android/debug.keystore，簽章不同的新版裝不上去（不能覆蓋舊版）
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.documentfile:documentfile:1.0.1")
}
