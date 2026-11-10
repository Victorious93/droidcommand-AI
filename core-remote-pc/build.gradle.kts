// Remote PC ("My PC") hub tile: pure-JVM logic for reading VictorSuite's PcProfileProvider rows,
// building the OPEN_PC handoff, the online probe, and the saved-command confirm flow. The Android
// glue (ContentResolver, Intent, Compose tile) lives in :app. Spec: Victorious93/VictorSuite
// docs/specs/VICTORPC_PAIRING.md (v1, VictorSuite #99). This module holds no SSH key and no secret.
plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
