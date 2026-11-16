// Victorious Framework — System Settings engine (JVM-only, device-free).
//
// One typed engine for the Android System/Secure/Global settings tables. The app's UI, saved profiles, the assistant's tools and
// any future remote path all call the same `SystemSettingsEngine`, so validation, the write-ahead journal and restoration behave the
// same everywhere. Android-specific pieces (ContentResolver, DisplayManager, boot receiver) live in :app behind small interfaces.
//
// Independent implementation. The idea of a SetEditPlus-style settings editor was inspired by
// https://github.com/kerneldroid/SetEditPlus (MIT, (c) 2026 kerneldroid); no source from it is copied here. See docs/SYSTEM_SETTINGS.md.
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-security"))
    implementation(project(":core-shizuku"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
