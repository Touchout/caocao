import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 遊戲目錄和檔名有中文，JVM 要用 UTF-8 locale 才讀得到
    environment("LC_ALL", "C.UTF-8")
    // 有遊戲檔時跑整合測試：./gradlew :core:test -Pgame=<遊戲目錄>
    project.findProperty("game")?.let { systemProperty("ccz.game", it) }
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
