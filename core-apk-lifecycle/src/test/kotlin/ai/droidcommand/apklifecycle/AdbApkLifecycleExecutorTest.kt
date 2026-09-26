package ai.droidcommand.apklifecycle

import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [AdbApkLifecycleExecutor]'s real `adb` command sequences against a real, scripted `adb` shell
 * script spawned as a real subprocess (via a real [ProcessBuilderShellExecutor]) — mirroring
 * `core-tools-android.AdbDeviceControllerTest`'s "prove the boundary is real" convention. No real device
 * is available in this environment, so real-device verification remains a named, open gap (see
 * `docs/AUDIT_2026-09-05.md`'s "AdbApkLifecycleExecutor" addendum).
 */
class AdbApkLifecycleExecutorTest {
    private lateinit var tempDir: File
    private val packageName = "com.example.app"
    private val testPackage = "com.example.app.test"
    private val runnerClass = "androidx.test.runner.AndroidJUnitRunner"

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("adb-apk-lifecycle-executor-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeScript(name: String, content: String): String {
        val file = File(tempDir, name)
        file.writeText(content)
        file.setExecutable(true)
        return file.absolutePath
    }

    private fun newExecutor(adbPath: String) = AdbApkLifecycleExecutor(
        shell = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf(adbPath))),
        adbExecutable = adbPath,
    )

    private val happyPathScript = """
        #!/bin/sh
        case "${'$'}1" in
          install)
            echo "Performing Streamed Install"
            echo "Success"
            exit 0
            ;;
          uninstall)
            shift
            if [ "${'$'}1" = "$packageName" ]; then
              echo "Success"
              exit 0
            else
              echo "Failure"
              exit 1
            fi
            ;;
          logcat)
            shift
            case "${'$'}*" in
              "-d -v threadtime --pid=12345")
                echo "09-17 10:00:00.100  12345  12345 I MyTag: first message"
                echo "09-17 10:00:01.200  12345  12345 E MyTag: second message"
                ;;
              *)
                exit 1 ;;
            esac
            exit 0
            ;;
          shell)
            shift
            case "${'$'}*" in
              "dumpsys package $packageName")
                echo "Package [$packageName] (abcdef):"
                echo "    versionCode=42 minSdk=24 targetSdk=34"
                ;;
              "cmd package resolve-activity --brief $packageName")
                echo "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true"
                echo "$packageName/.MainActivity"
                ;;
              "am start -n $packageName/.MainActivity")
                echo "Starting: Intent { cmp=$packageName/.MainActivity }"
                ;;
              "pidof $packageName")
                echo "12345"
                ;;
              "pm list instrumentation")
                echo "instrumentation:$testPackage/$runnerClass (target=$packageName)"
                ;;
              "am instrument -w -r $testPackage/$runnerClass")
                cat <<'EOF'
        INSTRUMENTATION_STATUS: class=com.example.FooTest
        INSTRUMENTATION_STATUS: test=testPass
        INSTRUMENTATION_STATUS: current=1
        INSTRUMENTATION_STATUS: numtests=2
        INSTRUMENTATION_STATUS_CODE: 1
        INSTRUMENTATION_STATUS: class=com.example.FooTest
        INSTRUMENTATION_STATUS: test=testPass
        INSTRUMENTATION_STATUS_CODE: 0
        INSTRUMENTATION_STATUS: class=com.example.FooTest
        INSTRUMENTATION_STATUS: test=testFail
        INSTRUMENTATION_STATUS: current=2
        INSTRUMENTATION_STATUS: numtests=2
        INSTRUMENTATION_STATUS_CODE: 1
        INSTRUMENTATION_STATUS: class=com.example.FooTest
        INSTRUMENTATION_STATUS: test=testFail
        INSTRUMENTATION_STATUS: stack=junit.framework.AssertionFailedError: boom
        	at com.example.FooTest.testFail(FooTest.java:10)
        INSTRUMENTATION_STATUS_CODE: -2
        INSTRUMENTATION_RESULT: stream=
        Time: 0.045

        FAILURES!!!
        Tests run: 2,  Failures: 1
        INSTRUMENTATION_CODE: -1
        EOF
                ;;
              *)
                exit 1 ;;
            esac
            exit 0
            ;;
          *)
            exit 1
            ;;
        esac
    """.trimIndent()

    private fun newHappyPathExecutor() = newExecutor(writeScript("adb", happyPathScript))

    @Test
    fun `install reports real success and reads back a real version code`() {
        val result = assertIs<InstallResult.Success>(
            newHappyPathExecutor().install(InstallRequest(artifactPath = "/tmp/app-debug.apk", packageName = packageName)),
        )
        assertEquals(packageName, result.packageName)
        assertEquals(42L, result.versionCode)
    }

    @Test
    fun `install fails honestly when adb install reports Failure`() {
        val adbPath = writeScript(
            "adb-install-fail",
            """
            #!/bin/sh
            if [ "${'$'}1" = "install" ]; then
              echo "Failure [INSTALL_FAILED_INVALID_APK]"
              exit 1
            fi
            exit 1
            """.trimIndent(),
        )
        val result = assertIs<InstallResult.Failure>(
            newExecutor(adbPath).install(InstallRequest(artifactPath = "/tmp/bad.apk", packageName = packageName)),
        )
        assertTrue(result.reason.contains("INSTALL_FAILED_INVALID_APK"))
    }

    @Test
    fun `uninstall reports real success`() {
        val result = assertIs<UninstallResult.Success>(newHappyPathExecutor().uninstall(packageName))
        assertEquals(packageName, result.packageName)
    }

    @Test
    fun `uninstall fails honestly for an unknown package`() {
        val result = assertIs<UninstallResult.Failure>(newHappyPathExecutor().uninstall("com.example.unknown"))
        assertTrue(result.reason.contains("Failure"))
    }

    @Test
    fun `launch resolves the real launcher activity and starts it`() {
        val result = assertIs<LaunchResult.Success>(newHappyPathExecutor().launch(packageName))
        assertEquals("Started $packageName/.MainActivity", result.message)
    }

    @Test
    fun `launch fails honestly when no activity resolves`() {
        val adbPath = writeScript(
            "adb-no-activity",
            """
            #!/bin/sh
            if [ "${'$'}1" = "shell" ]; then
              shift
              case "${'$'}*" in
                "cmd package resolve-activity --brief $packageName")
                  echo "No activity found" ;;
                *) exit 1 ;;
              esac
              exit 0
            fi
            exit 1
            """.trimIndent(),
        )
        val result = assertIs<LaunchResult.Failure>(newExecutor(adbPath).launch(packageName))
        assertTrue(result.reason.contains("no launchable activity"))
    }

    @Test
    fun `collectLogs parses real threadtime output for the app's own pid`() {
        val result = assertIs<LogsResult.Success>(newHappyPathExecutor().collectLogs(packageName))
        assertEquals(2, result.entries.size)
        assertEquals(LogLevel.INFO, result.entries[0].level)
        assertEquals("MyTag", result.entries[0].tag)
        assertEquals("first message", result.entries[0].message)
        assertEquals(LogLevel.ERROR, result.entries[1].level)
        assertEquals("second message", result.entries[1].message)
    }

    @Test
    fun `collectLogs sinceMillis filters out earlier entries`() {
        val all = assertIs<LogsResult.Success>(newHappyPathExecutor().collectLogs(packageName))
        val secondTimestamp = all.entries[1].timestamp
        val filtered = assertIs<LogsResult.Success>(
            newHappyPathExecutor().collectLogs(packageName, sinceMillis = secondTimestamp.toEpochMilli()),
        )
        assertEquals(1, filtered.entries.size)
        assertEquals("second message", filtered.entries[0].message)
    }

    @Test
    fun `collectLogs fails honestly when the process is not running`() {
        val adbPath = writeScript(
            "adb-no-pid",
            """
            #!/bin/sh
            if [ "${'$'}1" = "shell" ]; then
              exit 1
            fi
            exit 1
            """.trimIndent(),
        )
        val result = assertIs<LogsResult.Failure>(newExecutor(adbPath).collectLogs(packageName))
        assertTrue(result.reason.contains("no running process"))
    }

    @Test
    fun `runInstrumentedTests discovers the runner and parses real pass and failure results`() {
        val result = assertIs<TestRunResult.Success>(newHappyPathExecutor().runInstrumentedTests(packageName, testPackage))
        assertEquals(2, result.results.size)

        val passed = result.results[0]
        assertEquals("com.example.FooTest", passed.className)
        assertEquals("testPass", passed.methodName)
        assertEquals(TestOutcome.PASSED, passed.outcome)
        assertNull(passed.failureMessage)

        val failed = result.results[1]
        assertEquals("testFail", failed.methodName)
        assertEquals(TestOutcome.FAILED, failed.outcome)
        val failureMessage = assertNotNull(failed.failureMessage)
        assertTrue(failureMessage.contains("AssertionFailedError: boom"))
        assertTrue(failureMessage.contains("FooTest.testFail(FooTest.java:10)"))
    }

    @Test
    fun `runInstrumentedTests without an explicit testPackage matches by declared target`() {
        val result = assertIs<TestRunResult.Success>(newHappyPathExecutor().runInstrumentedTests(packageName))
        assertEquals(2, result.results.size)
    }

    @Test
    fun `runInstrumentedTests fails honestly when no instrumentation is registered`() {
        val adbPath = writeScript(
            "adb-no-instrumentation",
            """
            #!/bin/sh
            if [ "${'$'}1" = "shell" ]; then
              shift
              case "${'$'}*" in
                "pm list instrumentation")
                  echo "instrumentation:com.other.test/some.Runner (target=com.other)" ;;
                *) exit 1 ;;
              esac
              exit 0
            fi
            exit 1
            """.trimIndent(),
        )
        val result = assertIs<TestRunResult.Failure>(newExecutor(adbPath).runInstrumentedTests(packageName))
        assertTrue(result.reason.contains("no instrumentation registered"))
    }
}
