package ai.droidcommand.termux

import ai.droidcommand.root.RootCommand
import ai.droidcommand.root.RootExecutionResult
import ai.droidcommand.root.RootExecutor
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves [AdbTermuxExecutor]'s detection/dispatch/readback logic against a real, controlled fixture (a
 * real scripted `adb` shell script, spawned as a real subprocess) rather than a mock, mirroring
 * `core-root.AdbRootExecutorTest`'s "prove the boundary is real" convention. The fixture `adb` script
 * genuinely receives the RUN_COMMAND dispatch argv and records it, so the extras this class builds are
 * asserted against real (if simulated) process argv, not a hand-typed expectation. Output readback goes
 * through a real, injected fake [RootExecutor] standing in for "the device's Termux home + root cat/rm
 * access" — real (if simulated) call sequencing, not a mock verifying method calls. No real Termux/adb
 * device exists in the environment this test was written in (confirmed: `adb` is not installed) — real
 * on-device verification is real follow-up work, named honestly in `AdbTermuxExecutor`'s own doc comment.
 */
class AdbTermuxExecutorTest {
    private lateinit var tempDir: File
    private lateinit var recorderFile: File

    @BeforeTest
    fun setUp() {
        tempDir = createTempDirectory("adb-termux-executor-test").toFile()
        recorderFile = File(tempDir, "recorded-dispatch.txt")
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

    private fun adbScript(deviceConnected: Boolean = true, termuxInstalled: Boolean = true, dispatchExitCode: Int = 0): String = """
        #!/bin/sh
        if [ "${'$'}1" = "get-state" ]; then
          ${if (deviceConnected) "echo \"device\"; exit 0" else "exit 1"}
        fi
        if [ "${'$'}1" = "shell" ]; then
          shift
          if [ "${'$'}1" = "pm" ]; then
            ${if (termuxInstalled) "echo \"package:com.termux\"; exit 0" else "exit 0"}
          fi
          if [ "${'$'}1" = "am" ]; then
            echo "${'$'}*" > '${recorderFile.absolutePath}'
            exit $dispatchExitCode
          fi
          exit 1
        fi
        exit 1
        """.trimIndent()

    private class FakeRootExecutor(
        private val available: Boolean = true,
        private val exitContent: String? = "0\n",
        private val outContent: String = "",
        private val errContent: String = "",
        private val failExitCatsBeforeSuccess: Int = 0,
    ) : RootExecutor {
        var exitCatCallCount = 0
            private set
        var rmCallCount = 0
            private set
        val commands = mutableListOf<RootCommand>()
        var rmFails = false

        override fun isRootAvailable(): Boolean = available

        override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
            commands.add(command)
            return when (command.executable) {
                "cat" -> {
                    val path = command.args.first()
                    when {
                        path.endsWith(".exit") -> {
                            exitCatCallCount++
                            if (exitContent == null || exitCatCallCount <= failExitCatsBeforeSuccess) {
                                RootExecutionResult.Success(1, "", "cat: no such file", 0)
                            } else {
                                RootExecutionResult.Success(0, exitContent, "", 0)
                            }
                        }
                        path.endsWith(".out") -> RootExecutionResult.Success(0, outContent, "", 0)
                        path.endsWith(".err") -> RootExecutionResult.Success(0, errContent, "", 0)
                        else -> RootExecutionResult.Failure("unexpected cat path: $path")
                    }
                }
                "rm" -> {
                    rmCallCount++
                    if (rmFails) RootExecutionResult.Failure("permission denied") else RootExecutionResult.Success(0, "", "", 0)
                }
                else -> RootExecutionResult.Failure("unexpected executable: ${command.executable}")
            }
        }
    }

    private fun executor(
        adbScriptContent: String = adbScript(),
        rootExecutor: RootExecutor = FakeRootExecutor(),
        pollIntervalMillis: Long = 20,
    ) = AdbTermuxExecutor(
        rootExecutor = rootExecutor,
        adbExecutable = writeScript("adb", adbScriptContent),
        pollIntervalMillis = pollIntervalMillis,
        dispatchTimeoutMillis = 2_000,
    )

    @Test
    fun `isDeviceConnected reflects a real adb get-state call`() {
        assertTrue(executor(adbScript(deviceConnected = true)).isDeviceConnected())
        assertFalse(executor(adbScript(deviceConnected = false)).isDeviceConnected())
    }

    @Test
    fun `isTermuxInstalled reflects a real adb pm list packages call`() {
        assertTrue(executor(adbScript(termuxInstalled = true)).isTermuxInstalled())
        assertFalse(executor(adbScript(termuxInstalled = false)).isTermuxInstalled())
    }

    @Test
    fun `isAvailable requires device, Termux and root all together`() {
        assertTrue(executor(rootExecutor = FakeRootExecutor(available = true)).isAvailable())
        assertFalse(executor(rootExecutor = FakeRootExecutor(available = false)).isAvailable())
        assertFalse(executor(adbScript(deviceConnected = false)).isAvailable())
        assertFalse(executor(adbScript(termuxInstalled = false)).isAvailable())
    }

    @Test
    fun `execute fails without dispatching when no device is connected`() {
        val result = assertIs<TermuxExecutionResult.Failure>(
            executor(adbScript(deviceConnected = false)).execute(TermuxCommand("echo", listOf("hi"))),
        )
        assertTrue(result.reason.contains("No authorized adb device"))
        assertFalse(recorderFile.exists())
    }

    @Test
    fun `execute fails without dispatching when Termux is not installed`() {
        val result = assertIs<TermuxExecutionResult.Failure>(
            executor(adbScript(termuxInstalled = false)).execute(TermuxCommand("echo", listOf("hi"))),
        )
        assertTrue(result.reason.contains("not installed"))
        assertFalse(recorderFile.exists())
    }

    @Test
    fun `execute fails without dispatching when root is unavailable`() {
        val result = assertIs<TermuxExecutionResult.Failure>(
            executor(rootExecutor = FakeRootExecutor(available = false)).execute(TermuxCommand("echo", listOf("hi"))),
        )
        assertTrue(result.reason.contains("Root is unavailable"))
        assertFalse(recorderFile.exists())
    }

    @Test
    fun `a successful round trip retrieves real stdout, stderr and exit code`() {
        val root = FakeRootExecutor(exitContent = "0\n", outContent = "hi\n", errContent = "")
        val result = assertIs<TermuxExecutionResult.Success>(
            executor(rootExecutor = root).execute(TermuxCommand("echo", listOf("hi"))),
        )
        assertEquals(0, result.exitCode)
        assertEquals("hi\n", result.stdout)
        assertEquals("", result.stderr)
        assertEquals(1, root.rmCallCount)
    }

    @Test
    fun `argv, workingDirectory and environment are folded into the dispatched RUN_COMMAND arguments`() {
        executor().execute(
            TermuxCommand(
                executable = "pkg",
                args = listOf("install", "python"),
                workingDirectory = "/data/data/com.termux/files/home/project",
                environment = mapOf("FOO" to "bar"),
            ),
        )

        val recorded = recorderFile.readText()
        assertTrue(recorded.contains("com.termux/com.termux.app.RunCommandService"))
        assertTrue(recorded.contains("com.termux.RUN_COMMAND"))
        assertTrue(recorded.contains("com.termux.RUN_COMMAND_PATH"))
        assertTrue(recorded.contains("com.termux.RUN_COMMAND_ARGUMENTS"))
        assertTrue(recorded.contains("com.termux.RUN_COMMAND_BACKGROUND"))

        // The recorded line is itself shell-quoted for the remote-shell-flattening layer
        // (see AdbTermuxExecutor.dispatchRunCommand's doc comment) — undo that one layer to
        // recover the real, unescaped "-c,<wrapped command>" value the RUN_COMMAND_ARGUMENTS
        // extra actually carries, rather than asserting against its escaped form.
        val marker = "com.termux.RUN_COMMAND_ARGUMENTS '"
        val start = recorded.indexOf(marker) + marker.length
        val end = recorded.indexOf("' --ez", start)
        val argumentsValue = recorded.substring(start, end).replace("'\\''", "'")
        val wrapped = argumentsValue.split(",", limit = 2)[1]

        assertTrue(wrapped.contains("mkdir -p"))
        assertTrue(wrapped.contains("cd '/data/data/com.termux/files/home/project'"))
        assertTrue(wrapped.contains("FOO='bar'"))
        assertTrue(wrapped.contains("'pkg' 'install' 'python'"))
    }

    @Test
    fun `a non-zero dispatch exit code fails without polling for a result`() {
        val root = FakeRootExecutor()
        val result = assertIs<TermuxExecutionResult.Failure>(
            executor(adbScript(dispatchExitCode = 1), rootExecutor = root).execute(TermuxCommand("echo")),
        )
        assertTrue(result.reason.contains("Failed to dispatch"))
        assertEquals(0, root.exitCatCallCount)
    }

    @Test
    fun `polling retries until the exit marker appears`() {
        val root = FakeRootExecutor(exitContent = "0\n", failExitCatsBeforeSuccess = 2)
        val result = assertIs<TermuxExecutionResult.Success>(
            executor(rootExecutor = root).execute(TermuxCommand("echo", timeoutMillis = 2_000)),
        )
        assertEquals(0, result.exitCode)
        assertTrue(root.exitCatCallCount >= 3)
    }

    @Test
    fun `a poll timeout is reported honestly rather than fabricating a result`() {
        val root = FakeRootExecutor(exitContent = null)
        val result = assertIs<TermuxExecutionResult.Failure>(
            executor(rootExecutor = root).execute(TermuxCommand("sleep", listOf("60"), timeoutMillis = 200)),
        )
        assertTrue(result.reason.contains("timed out"))
    }

    @Test
    fun `cleanup failure is logged but never fails an otherwise-successful result`() {
        val root = FakeRootExecutor().apply { rmFails = true }
        val result = assertIs<TermuxExecutionResult.Success>(executor(rootExecutor = root).execute(TermuxCommand("echo")))
        assertEquals(0, result.exitCode)
        assertEquals(1, root.rmCallCount)
    }

    @Test
    fun `encodeRunCommandArguments comma-separates elements and escapes literal commas and backslashes`() {
        assertEquals("-c,echo hi", encodeRunCommandArguments(listOf("-c", "echo hi")))
        assertEquals("a\\,b", encodeRunCommandArguments(listOf("a,b")))
        assertEquals("a\\\\b", encodeRunCommandArguments(listOf("a\\b")))
    }

    @Test
    fun `shellQuote neutralizes an embedded single quote`() {
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }
}
