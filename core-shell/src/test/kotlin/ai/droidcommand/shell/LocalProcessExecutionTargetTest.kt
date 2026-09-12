package ai.droidcommand.shell

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.PrivilegeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingShellExecutor(private val result: ShellExecutionResult) : ShellExecutor {
    var callCount = 0
        private set
    var lastCommand: ShellCommand? = null
        private set

    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        callCount++
        lastCommand = command
        return result
    }
}

private fun context() = ExecutionContext(
    workingDir = "/home/user",
    user = "user",
    environment = emptyMap(),
    privilegeLevel = PrivilegeLevel.USER,
)

class LocalProcessExecutionTargetTest {
    @Test
    fun `type is always LOCAL_PC`() {
        val target = LocalProcessExecutionTarget("local-1", context(), RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0)))
        assertEquals(ExecutionTargetType.LOCAL_PC, target.type)
    }

    @Test
    fun `isHealthy is always true`() {
        val target = LocalProcessExecutionTarget("local-1", context(), RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0)))
        assertTrue(target.isHealthy())
    }

    @Test
    fun `id, context and availableCapabilities are passed through`() {
        val caps = setOf(CapabilityId("local_pc.shell"))
        val target = LocalProcessExecutionTarget("local-1", context(), RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0)), caps)
        assertEquals("local-1", target.id)
        assertEquals(context(), target.context)
        assertEquals(caps, target.availableCapabilities)
    }

    @Test
    fun `availableCapabilities defaults to empty`() {
        val target = LocalProcessExecutionTarget("local-1", context(), RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0)))
        assertEquals(emptySet(), target.availableCapabilities)
    }

    @Test
    fun `empty argv fails without calling the underlying executor`() {
        val executor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = LocalProcessExecutionTarget("local-1", context(), executor)

        val result = target.execute(emptyList())

        assertEquals(0, executor.callCount)
        assertEquals(-1, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
        assertEquals(ExecutionTargetType.LOCAL_PC, result.target)
    }

    @Test
    fun `argv is split into executable and args, workingDir-env-timeout are forwarded`() {
        val executor = RecordingShellExecutor(ShellExecutionResult.Success(0, "out", "", 5))
        val target = LocalProcessExecutionTarget("local-1", context(), executor)

        target.execute(
            argv = listOf("echo", "hello", "world"),
            workingDir = "/tmp",
            env = mapOf("FOO" to "bar"),
            timeoutMs = 5_000,
        )

        val command = executor.lastCommand!!
        assertEquals("echo", command.executable)
        assertEquals(listOf("hello", "world"), command.args)
        assertEquals("/tmp", command.workingDirectory)
        assertEquals(mapOf("FOO" to "bar"), command.environment)
        assertEquals(5_000, command.timeoutMillis)
    }

    @Test
    fun `a null env forwards an empty map, not null`() {
        val executor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = LocalProcessExecutionTarget("local-1", context(), executor)

        target.execute(argv = listOf("true"), env = null)

        assertEquals(emptyMap(), executor.lastCommand!!.environment)
    }

    @Test
    fun `a Success result maps exit code, stdout and stderr, never timed out, never verified`() {
        val executor = RecordingShellExecutor(ShellExecutionResult.Success(exitCode = 3, stdout = "out", stderr = "err", durationMillis = 10))
        val target = LocalProcessExecutionTarget("local-1", context(), executor)

        val result = target.execute(listOf("some-command"))

        assertEquals(3, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
        assertEquals(ExecutionTargetType.LOCAL_PC, result.target)
    }

    @Test
    fun `a non-timeout Failure maps to exit code -1 with timedOut false`() {
        val executor = RecordingShellExecutor(ShellExecutionResult.Failure("Executable 'rm' is not in the allowed executable list"))
        val target = LocalProcessExecutionTarget("local-1", context(), executor)

        val result = target.execute(listOf("rm", "-rf", "/"))

        assertEquals(-1, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("not in the allowed executable list"))
        assertFalse(result.timedOut)
        assertFalse(result.verified)
    }

    @Test
    fun `a timeout Failure is reported as timedOut true`() {
        val executor = RecordingShellExecutor(ShellExecutionResult.Failure("Command timed out after 30000ms"))
        val target = LocalProcessExecutionTarget("local-1", context(), executor)

        val result = target.execute(listOf("sleep", "60"), timeoutMs = 30_000)

        assertEquals(-1, result.exitCode)
        assertTrue(result.timedOut)
        assertFalse(result.verified)
    }
}
