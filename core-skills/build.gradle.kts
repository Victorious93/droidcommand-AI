plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // MCP tool bridge (ai.droidcommand.skills.mcp): MCP tools become ordinary core-agent Tools, run through the
    // same ToolRunner / SecureToolExecutor / ApprovalPrompt path as every other tool, and the built-in HTTP source
    // talks through core-remote's HttpTransport (no extra network library on the phone).
    implementation(project(":core-agent"))
    implementation(project(":core-security"))
    implementation(project(":core-remote"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(project(":core-bots"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
