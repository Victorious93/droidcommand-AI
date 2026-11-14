// EXPERIMENTAL Android-to-Android screen-control adapter: POLICY, LIMITS and SESSION LIFECYCLE only (pure JVM).
// The ADB client, scrcpy-style server launch and video decoder are NOT implemented here (no device was available and
// the reference app is GPL-3.0; see docs/SCREEN_CONTROL_RESEARCH.md). Disabled by default; physical validation UNVERIFIED.
plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
