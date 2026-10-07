plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
    testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
    // Test-only: proves McpToolServer's executor param (typed as the
    // core-agent.ToolRunner interface) genuinely accepts a real
    // SecureToolExecutor, not just that the type signature allows it.
    // core-mcp's *main* source has no core-security dependency and does
    // not need one — only this test does.
    testImplementation(project(":core-security"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
