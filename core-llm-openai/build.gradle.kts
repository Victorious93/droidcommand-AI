plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    // StrictChatServer (src/testFixtures): the controlled server that rejects malformed tool transcripts, shared with
    // the modules whose tests drive this adapter (core-llm-factory, core-integration-tests). Never part of main.
    `java-test-fixtures`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-llm"))
    implementation(project(":core-remote"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testFixturesImplementation(project(":core-agent"))
    testFixturesApi("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
