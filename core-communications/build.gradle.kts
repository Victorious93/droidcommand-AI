// Victorious Framework: COMPANION_COMMS v1 broker. Pure JVM, no Android. The unattended-capable contract that
// VictorBots and VictorSMS use to ask droidcommand-AI for AI work and authorised communications.
// The Android transport (bound service) and the VictorSMS/VictorBots connectors are injected from :app.
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
