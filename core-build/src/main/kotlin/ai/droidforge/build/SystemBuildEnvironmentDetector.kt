package ai.droidforge.build

import java.io.File

/**
 * Real capability detection with no process spawning: JDK presence comes
 * from the JVM this code is already running on (`System.getProperty`);
 * everything else is an environment-variable lookup plus a filesystem
 * existence check, or a [PathExecutableDetector] scan of `PATH`. [env],
 * [fileExists], and [javaHome] are injectable so every test runs against a
 * fixture, never this process's real environment — the same pattern
 * `core-config`'s `EnvConfigSource` already established.
 */
class SystemBuildEnvironmentDetector(
    private val env: (String) -> String? = System::getenv,
    private val fileExists: (String) -> Boolean = { File(it).exists() },
    private val javaHome: () -> String = { System.getProperty("java.home") },
    private val javaVersion: () -> String = { System.getProperty("java.version") },
    private val pathExecutableDetector: PathExecutableDetector = PathExecutableDetector(),
) : BuildEnvironmentDetector {
    override fun check(tool: EnvironmentTool): ToolCheckResult = when (tool) {
        EnvironmentTool.JDK -> ToolCheckResult(tool, ToolAvailability.AVAILABLE, javaVersion())
        EnvironmentTool.GRADLE -> checkExecutableOnPath(tool, "gradle")
        EnvironmentTool.GIT -> checkExecutableOnPath(tool, "git")
        EnvironmentTool.ANDROID_SDK -> checkSdkRoot(tool)
        EnvironmentTool.ANDROID_BUILD_TOOLS -> checkSdkSubpath(tool, "build-tools")
        EnvironmentTool.ADB -> checkSdkSubpath(tool, "platform-tools/adb")
        EnvironmentTool.NDK -> checkNdk(tool)
        EnvironmentTool.SIGNING_TOOLS -> checkKeytool(tool)
    }

    private fun sdkRoot(): String? = env("ANDROID_HOME") ?: env("ANDROID_SDK_ROOT")

    private fun checkSdkRoot(tool: EnvironmentTool): ToolCheckResult {
        val root = sdkRoot()
        return if (root != null && fileExists(root)) {
            ToolCheckResult(tool, ToolAvailability.AVAILABLE, root)
        } else {
            ToolCheckResult(tool, ToolAvailability.UNAVAILABLE, "ANDROID_HOME/ANDROID_SDK_ROOT not set or path does not exist")
        }
    }

    private fun checkSdkSubpath(tool: EnvironmentTool, relativePath: String): ToolCheckResult {
        val root = sdkRoot() ?: return ToolCheckResult(tool, ToolAvailability.UNAVAILABLE, "ANDROID_HOME/ANDROID_SDK_ROOT not set")
        val candidate = "${root.trimEnd('/')}/$relativePath"
        return if (fileExists(candidate)) {
            ToolCheckResult(tool, ToolAvailability.AVAILABLE, candidate)
        } else {
            ToolCheckResult(tool, ToolAvailability.UNAVAILABLE, "Not found at $candidate")
        }
    }

    private fun checkNdk(tool: EnvironmentTool): ToolCheckResult {
        val explicit = env("ANDROID_NDK_HOME")
        if (explicit != null && fileExists(explicit)) {
            return ToolCheckResult(tool, ToolAvailability.AVAILABLE, explicit)
        }
        val root = sdkRoot() ?: return ToolCheckResult(tool, ToolAvailability.UNAVAILABLE, "Neither ANDROID_NDK_HOME nor an SDK root is set")
        val candidate = "${root.trimEnd('/')}/ndk"
        return if (fileExists(candidate)) {
            ToolCheckResult(tool, ToolAvailability.AVAILABLE, candidate)
        } else {
            ToolCheckResult(tool, ToolAvailability.UNAVAILABLE, "Not found at $candidate")
        }
    }

    private fun checkKeytool(tool: EnvironmentTool): ToolCheckResult {
        val candidate = "${javaHome().trimEnd('/')}/bin/keytool"
        return if (fileExists(candidate)) {
            ToolCheckResult(tool, ToolAvailability.AVAILABLE, candidate)
        } else {
            ToolCheckResult(tool, ToolAvailability.UNAVAILABLE, "keytool not found under java.home ($candidate)")
        }
    }

    private fun checkExecutableOnPath(tool: EnvironmentTool, executableName: String): ToolCheckResult =
        if (pathExecutableDetector.isOnPath(executableName)) {
            ToolCheckResult(tool, ToolAvailability.AVAILABLE, "Found '$executableName' on PATH")
        } else {
            ToolCheckResult(tool, ToolAvailability.UNAVAILABLE, "'$executableName' not found on PATH")
        }
}
