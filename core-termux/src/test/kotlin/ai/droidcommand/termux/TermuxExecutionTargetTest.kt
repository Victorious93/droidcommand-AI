package ai.droidcommand.termux

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.PrivilegeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingTermuxExecutor(
    private val available: Boolean,
    private val result: TermuxExecutionResult,
) : TermuxExecutor {
    var callCount = 0
        private set
    var lastCommand: TermuxCommand? = null
        private set

    override fun isAvailable(): Boolean = available

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        callCount++
        lastCommand = command
        return result
    }
}

private fun context() = ExecutionContext(
    workingDir = "/data/data/com.termux/files/home",
    user = "termux",
    environment = emptyMap(),
    privilegeLevel = PrivilegeLevel.USER,
)

class TermuxExecutionTargetTest {
    @Test
    fun `type is always TERMUX`() {
        val target = TermuxExecutionTarget("termux-1", context(), RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0)))
        assertEquals(ExecutionTargetType.TERMUX, target.type)
    }

    @Test
    fun `isHealthy reflects the underlying executor's isAvailable`() {
        val available = TermuxExecutionTarget("termux-1", context(), RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0)))
        val unavailable = TermuxExecutionTarget("termux-2", context(), RecordingTermuxExecutor(false, TermuxExecutionResult.Success(0, "", "", 0)))
        assertTrue(available.isHealthy())
        assertFalse(unavailable.isHealthy())
    }

    @Test
    fun `id, context and availableCapabilities are passed through`() {
        val caps = setOf(CapabilityId("termux.shell.execute"))
        val target = TermuxExecutionTarget("termux-1", context(), RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0)), caps)
        assertEquals("termux-1", target.id)
        assertEquals(context(), target.context)
        assertEquals(caps, target.availableCapabilities)
    }

    @Test
    fun `empty argv fails without calling the underlying executor`() {
        val executor = RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0))
        val result = TermuxExecutionTarget("termux-1", context(), executor).execute(emptyList())

        assertEquals(0, executor.callCount)
        assertEquals(-1, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
    }

    @Test
    fun `argv is split into executable and args, workingDir-env-timeout are forwarded`() {
        val executor = RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0))
        TermuxExecutionTarget("termux-1", context(), executor).execute(
            argv = listOf("echo", "hi"),
            workingDir = "/tmp",
            env = mapOf("FOO" to "bar"),
            timeoutMs = 5_000,
        )

        val command = executor.lastCommand!!
        assertEquals("echo", command.executable)
        assertEquals(listOf("hi"), command.args)
        assertEquals("/tmp", command.workingDirectory)
        assertEquals(mapOf("FOO" to "bar"), command.environment)
        assertEquals(5_000, command.timeoutMillis)
    }

    @Test
    fun `a null env forwards an empty map, not null`() {
        val executor = RecordingTermuxExecutor(true, TermuxExecutionResult.Success(0, "", "", 0))
        TermuxExecutionTarget("termux-1", context(), executor).execute(argv = listOf("echo"), env = null)

        assertEquals(emptyMap(), executor.lastCommand!!.environment)
    }

    @Test
    fun `a Success result maps exit code, stdout and stderr, never timed out, never verified`() {
        val executor = RecordingTermuxExecutor(true, TermuxExecutionResult.Success(exitCode = 0, stdout = "hi", stderr = "", durationMillis = 5))
        val result = TermuxExecutionTarget("termux-1", context(), executor).execute(listOf("echo", "hi"))

        assertEquals(0, result.exitCode)
        assertEquals("hi", result.stdout)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
        assertEquals(ExecutionTargetType.TERMUX, result.target)
    }

    @Test
    fun `a non-timeout Failure maps to exit code -1 with timedOut false`() {
        val executor = RecordingTermuxExecutor(true, TermuxExecutionResult.Failure("No authorized adb device connected"))
        val result = TermuxExecutionTarget("termux-1", context(), executor).execute(listOf("echo"))

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("device"))
        assertFalse(result.timedOut)
    }

    @Test
    fun `a timeout Failure is reported as timedOut true`() {
        val executor = RecordingTermuxExecutor(true, TermuxExecutionResult.Failure("Command timed out after 30000ms waiting for Termux to finish"))
        val result = TermuxExecutionTarget("termux-1", context(), executor).execute(listOf("sleep", "60"), timeoutMs = 30_000)

        assertEquals(-1, result.exitCode)
        assertTrue(result.timedOut)
    }
}
