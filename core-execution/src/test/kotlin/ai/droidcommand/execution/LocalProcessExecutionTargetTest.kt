package ai.droidcommand.execution

import ai.droidcommand.agent.ExecutionTargetType
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class ScriptedShellExecutor(private val result: ShellExecutionResult) : ShellExecutor {
    var lastCommand: ShellCommand? = null
        private set

    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        lastCommand = command
        return result
    }
}

private fun target(shellExecutor: ShellExecutor) = LocalProcessExecutionTarget(
    id = "local",
    context = ExecutionContext(
        workingDir = "/tmp",
        user = "test",
        environment = emptyMap(),
        privilegeLevel = PrivilegeLevel.USER,
    ),
    availableCapabilities = emptySet(),
    shellExecutor = shellExecutor,
)

class LocalProcessExecutionTargetTest {
    @Test
    fun `reports type LOCAL_PC and is always healthy`() {
        val t = target(ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0)))
        assertEquals(ExecutionTargetType.LOCAL_PC, t.type)
        assertTrue(t.isHealthy())
    }

    @Test
    fun `maps a Success result field-for-field with timedOut and verified false`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Success(exitCode = 0, stdout = "out", stderr = "err", durationMillis = 5))
        val result = target(shell).execute(listOf("echo", "hi"))

        assertEquals(ExecutionResult(exitCode = 0, stdout = "out", stderr = "err", timedOut = false, target = ExecutionTargetType.LOCAL_PC, verified = false), result)
    }

    @Test
    fun `maps a Failure result to exitCode -1 with the reason in stderr`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Failure("not in the allowed executable list"))
        val result = target(shell).execute(listOf("rm", "-rf"))

        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertEquals("not in the allowed executable list", result.stderr)
        assertEquals(false, result.timedOut)
        assertEquals(false, result.verified)
    }

    @Test
    fun `builds the ShellCommand from argv, workingDir, env, and timeoutMs`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        target(shell).execute(
            argv = listOf("echo", "hello", "world"),
            workingDir = "/authorized",
            env = mapOf("K" to "V"),
            timeoutMs = 1234,
        )

        val command = shell.lastCommand!!
        assertEquals("echo", command.executable)
        assertEquals(listOf("hello", "world"), command.args)
        assertEquals("/authorized", command.workingDirectory)
        assertEquals(mapOf("K" to "V"), command.environment)
        assertEquals(1234, command.timeoutMillis)
    }

    @Test
    fun `an empty argv is a caller-contract violation, not a runtime condition`() {
        val shell = ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        assertFailsWith<IllegalArgumentException> { target(shell).execute(emptyList()) }
    }

    @Test
    fun `real integration - spawns a genuine echo process through ProcessBuilderShellExecutor`() {
        val real = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("echo")))
        val result = target(real).execute(listOf("echo", "hello", "from", "execution", "target"))

        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("hello from execution target"))
        assertEquals(ExecutionTargetType.LOCAL_PC, result.target)
        assertEquals(false, result.verified)
    }

    @Test
    fun `real integration - a rejected executable maps to exitCode -1 without spawning`() {
        val real = ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("echo")))
        val result = target(real).execute(listOf("rm", "-rf", "/"))

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("not in the allowed"))
    }
}
