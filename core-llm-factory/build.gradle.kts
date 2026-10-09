plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core-agent"))
    implementation(project(":core-llm"))
    implementation(project(":core-config"))
    implementation(project(":core-remote"))
    implementation(project(":core-llm-anthropic"))
    implementation(project(":core-llm-openai"))
    implementation(project(":core-llm-google"))
    implementation(project(":core-llm-groq"))
    implementation(project(":core-llm-local"))
    api(project(":core-websearch"))
    api(project(":core-rag"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
