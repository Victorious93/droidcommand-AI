package ai.droidforge.build

enum class EnvironmentTool {
    JDK,
    GRADLE,
    ANDROID_SDK,
    ANDROID_BUILD_TOOLS,
    ADB,
    NDK,
    GIT,
    SIGNING_TOOLS,
}

enum class ToolAvailability {
    AVAILABLE,
    UNAVAILABLE,
    NOT_TESTED,
}

data class ToolCheckResult(
    val tool: EnvironmentTool,
    val availability: ToolAvailability,
    val detail: String? = null,
)
