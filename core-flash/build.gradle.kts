plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    // Reuses the pinned-host-key OpenSSH argv builder (SshCommand / SshTarget); nothing else.
    implementation(project(":core-rootforge"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
