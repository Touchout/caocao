import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 開發用：在沒有 Android 裝置的環境把戰場畫成 PNG，驗證 core 的資料與邏輯
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("ccz.desktop.PreviewKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    environment("LC_ALL", "C.UTF-8")
}

// 引擎端的劇本執行軌跡（docs/methodology.md 方法 2）
tasks.register<JavaExec>("trace") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ccz.desktop.TraceKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
    environment("LC_ALL", "C.UTF-8")
}
