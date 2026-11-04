plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-device-connect"))
    implementation(project(":core-remote"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("ai.droidcommand.pccompanion.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
