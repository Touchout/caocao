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
        versionCode = 42
        versionName = "0.4.2"
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
