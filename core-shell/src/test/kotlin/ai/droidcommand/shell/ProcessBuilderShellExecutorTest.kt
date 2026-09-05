package ai.droidcommand.shell

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * These tests spawn real OS subprocesses (`echo`, `true`, `false`,
 * `sleep`, `pwd`) — this is not mocked. All of them are standard POSIX
 * utilities present on any Linux/macOS environment this repository's own
 * tooling (Gradle, git) already assumes.
 */
class ProcessBuilderShellExecutorTest {
    @Test
    fun `rejects an executable outside the allow-list without starting a process`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("echo")))
        val result = assertIs<ShellExecutionResult.Failure>(executor.execute(ShellCommand(executable = "rm")))
        assertTrue(result.reason.contains("not in the allowed"))
    }

    @Test
    fun `runs an allow-listed command and captures stdout and exit code`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("echo")))
        val result = assertIs<ShellExecutionResult.Success>(executor.execute(ShellCommand(executable = "echo", args = listOf("hello", "world"))))
        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.stdout.trim())
    }

    @Test
    fun `reports a non-zero exit code from a real process`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("false")))
        val result = assertIs<ShellExecutionResult.Success>(executor.execute(ShellCommand(executable = "false")))
        assertEquals(1, result.exitCode)
    }

    @Test
    fun `reports exit code 0 for a real successful process`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("true")))
        val result = assertIs<ShellExecutionResult.Success>(executor.execute(ShellCommand(executable = "true")))
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `fails with a real IOException when the allow-listed executable does not actually exist`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("definitely-not-a-real-command-xyz")))
        val result = assertIs<ShellExecutionResult.Failure>(executor.execute(ShellCommand(executable = "definitely-not-a-real-command-xyz")))
        assertTrue(result.reason.contains("Failed to start"))
    }

    @Test
    fun `enforces a real timeout on a real long-running process`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("sleep")))
        val result = assertIs<ShellExecutionResult.Failure>(
            executor.execute(ShellCommand(executable = "sleep", args = listOf("5"), timeoutMillis = 200)),
        )
        assertTrue(result.reason.contains("timed out"))
    }

    @Test
    fun `honors cancellation on a real long-running process`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("sleep")))
        val result = assertIs<ShellExecutionResult.Failure>(
            executor.execute(ShellCommand(executable = "sleep", args = listOf("5")), isCancelled = { true }),
        )
        assertTrue(result.reason.contains("cancelled"))
    }

    @Test
    fun `denies a working-directory override when none is authorized`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("pwd")))
        val result = assertIs<ShellExecutionResult.Failure>(
            executor.execute(ShellCommand(executable = "pwd", workingDirectory = "/tmp")),
        )
        assertTrue(result.reason.contains("authorized"))
    }

    @Test
    fun `runs in an authorized working directory for real`() {
        val tempDir = Files.createTempDirectory("droidcommand-shell-test").toFile()
        try {
            val executor = ProcessBuilderShellExecutor(
                ShellSecurityPolicy(allowedExecutables = setOf("pwd"), allowedWorkingDirectories = listOf(tempDir.path)),
            )
            val result = assertIs<ShellExecutionResult.Success>(
                executor.execute(ShellCommand(executable = "pwd", workingDirectory = tempDir.path)),
            )
            assertEquals(tempDir.canonicalPath, result.stdout.trim())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `denies a working directory outside the authorized root, even a real existing one`() {
        val authorizedDir = Files.createTempDirectory("droidcommand-shell-authorized").toFile()
        val outsideDir = Files.createTempDirectory("droidcommand-shell-outside").toFile()
        try {
            val executor = ProcessBuilderShellExecutor(
                ShellSecurityPolicy(allowedExecutables = setOf("pwd"), allowedWorkingDirectories = listOf(authorizedDir.path)),
            )
            val result = assertIs<ShellExecutionResult.Failure>(
                executor.execute(ShellCommand(executable = "pwd", workingDirectory = outsideDir.path)),
            )
            assertTrue(result.reason.contains("not authorized"))
        } finally {
            authorizedDir.deleteRecursively()
            outsideDir.deleteRecursively()
        }
    }

    @Test
    fun `passes extra environment variables through to the real process`() {
        // env(1) is standard on Linux/macOS and simply prints the process environment.
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("env")))
        val result = assertIs<ShellExecutionResult.Success>(
            executor.execute(ShellCommand(executable = "env", environment = mapOf("DROIDCOMMAND_TEST_VAR" to "test-value-123"))),
        )
        assertTrue(result.stdout.contains("DROIDCOMMAND_TEST_VAR=test-value-123"))
    }

    @Test
    fun `truncates captured output once maxOutputBytes is reached, without hanging`() {
        val executor = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("seq")))
        val result = assertIs<ShellExecutionResult.Success>(
            executor.execute(ShellCommand(executable = "seq", args = listOf("1", "1000000"), maxOutputBytes = 100)),
        )
        assertTrue(result.stdout.length < 1000)
    }
}
