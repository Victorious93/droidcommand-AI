package ai.droidcommand.build

import kotlin.test.Test
import kotlin.test.assertEquals

class SystemBuildEnvironmentDetectorTest {
    @Test
    fun `JDK is always reported available`() {
        val detector = SystemBuildEnvironmentDetector(env = { null }, fileExists = { false })
        val result = detector.check(EnvironmentTool.JDK)
        assertEquals(ToolAvailability.AVAILABLE, result.availability)
    }

    @Test
    fun `ANDROID_SDK is unavailable when no SDK env var is set`() {
        val detector = SystemBuildEnvironmentDetector(env = { null }, fileExists = { false })
        assertEquals(ToolAvailability.UNAVAILABLE, detector.check(EnvironmentTool.ANDROID_SDK).availability)
    }

    @Test
    fun `ANDROID_SDK is available when ANDROID_HOME points at an existing path`() {
        val detector = SystemBuildEnvironmentDetector(
            env = { key -> if (key == "ANDROID_HOME") "/fake/sdk" else null },
            fileExists = { it == "/fake/sdk" },
        )
        assertEquals(ToolAvailability.AVAILABLE, detector.check(EnvironmentTool.ANDROID_SDK).availability)
    }

    @Test
    fun `ANDROID_SDK falls back to ANDROID_SDK_ROOT when ANDROID_HOME is unset`() {
        val detector = SystemBuildEnvironmentDetector(
            env = { key -> if (key == "ANDROID_SDK_ROOT") "/fake/sdk-root" else null },
            fileExists = { it == "/fake/sdk-root" },
        )
        assertEquals(ToolAvailability.AVAILABLE, detector.check(EnvironmentTool.ANDROID_SDK).availability)
    }

    @Test
    fun `ADB is unavailable when the platform-tools binary does not exist`() {
        val detector = SystemBuildEnvironmentDetector(
            env = { key -> if (key == "ANDROID_HOME") "/fake/sdk" else null },
            fileExists = { it == "/fake/sdk" },
        )
        assertEquals(ToolAvailability.UNAVAILABLE, detector.check(EnvironmentTool.ADB).availability)
    }

    @Test
    fun `ADB is available when the platform-tools binary exists under the SDK root`() {
        val detector = SystemBuildEnvironmentDetector(
            env = { key -> if (key == "ANDROID_HOME") "/fake/sdk" else null },
            fileExists = { it == "/fake/sdk" || it == "/fake/sdk/platform-tools/adb" },
        )
        assertEquals(ToolAvailability.AVAILABLE, detector.check(EnvironmentTool.ADB).availability)
    }

    @Test
    fun `SIGNING_TOOLS is available when keytool exists under java_home`() {
        val detector = SystemBuildEnvironmentDetector(
            javaHome = { "/fake/jdk" },
            fileExists = { it == "/fake/jdk/bin/keytool" },
        )
        assertEquals(ToolAvailability.AVAILABLE, detector.check(EnvironmentTool.SIGNING_TOOLS).availability)
    }

    @Test
    fun `checkAll aggregates results for every requested tool`() {
        val detector = SystemBuildEnvironmentDetector(env = { null }, fileExists = { false })
        val results = detector.checkAll(setOf(EnvironmentTool.JDK, EnvironmentTool.ANDROID_SDK, EnvironmentTool.NDK))
        assertEquals(3, results.size)
        assertEquals(ToolAvailability.AVAILABLE, results.single { it.tool == EnvironmentTool.JDK }.availability)
        assertEquals(ToolAvailability.UNAVAILABLE, results.single { it.tool == EnvironmentTool.ANDROID_SDK }.availability)
        assertEquals(ToolAvailability.UNAVAILABLE, results.single { it.tool == EnvironmentTool.NDK }.availability)
    }
}
