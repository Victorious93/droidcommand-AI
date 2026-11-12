plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // No src/main: this module exists solely to hold cross-module
    // integration tests that need two sibling modules (core-shell,
    // core-root) together, which neither depends on the other and no
    // existing module depends on both. testImplementation only, since
    // there is no production API of its own for anything to consume.
    testImplementation(project(":core-agent"))
    testImplementation(project(":core-security"))
    testImplementation(project(":core-shell"))
    testImplementation(project(":core-root"))
    testImplementation(project(":core-termux"))
    testImplementation(project(":core-shizuku"))
    testImplementation(project(":core-tools-android"))
    testImplementation(project(":core-tools-metasploit"))
    testImplementation(project(":core-tools-setoolkit"))
    testImplementation(project(":core-pentest-swarm"))
    testImplementation(project(":core-remotecontrol"))
    // Helper-bot tool execution end to end: ChatSession (core-llm-factory) driving the real SecureToolExecutor
    // (core-security) behind a bot's ScopedToolRunner (core-bots), against the strict controlled chat server.
    testImplementation(project(":core-llm"))
    testImplementation(project(":core-llm-factory"))
    testImplementation(project(":core-bots"))
    testImplementation(project(":core-config"))
    testImplementation(project(":core-remote"))
    testImplementation(testFixtures(project(":core-llm-openai")))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
