package ai.droidcommand.root

import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.PrivilegeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingRootExecutor(
    private val available: Boolean,
    private val result: RootExecutionResult,
) : RootExecutor {
    var callCount = 0
        private set
    var lastCommand: RootCommand? = null
        private set

    override fun isRootAvailable(): Boolean = available

    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
        callCount++
        lastCommand = command
        return result
    }
}

private fun context() = ExecutionContext(
    workingDir = "/",
    user = "root",
    environment = emptyMap(),
    privilegeLevel = PrivilegeLevel.ROOT,
)

class RootExecutionTargetTest {
    @Test
    fun `type is always ANDROID`() {
        val target = RootExecutionTarget("root-1", context(), RecordingRootExecutor(true, RootExecutionResult.Success(0, "", "", 0)))
        assertEquals(ExecutionTargetType.ANDROID, target.type)
    }

    @Test
    fun `isHealthy reflects the underlying executor's isRootAvailable`() {
        val available = RootExecutionTarget("root-1", context(), RecordingRootExecutor(true, RootExecutionResult.Success(0, "", "", 0)))
        val unavailable = RootExecutionTarget("root-2", context(), RecordingRootExecutor(false, RootExecutionResult.Success(0, "", "", 0)))
        assertTrue(available.isHealthy())
        assertFalse(unavailable.isHealthy())
    }

    @Test
    fun `id, context and availableCapabilities are passed through`() {
        val caps = setOf(CapabilityId("root.shell"))
        val target = RootExecutionTarget("root-1", context(), RecordingRootExecutor(true, RootExecutionResult.Success(0, "", "", 0)), caps)
        assertEquals("root-1", target.id)
        assertEquals(context(), target.context)
        assertEquals(caps, target.availableCapabilities)
    }

    @Test
    fun `empty argv fails without calling the underlying executor`() {
        val executor = RecordingRootExecutor(true, RootExecutionResult.Success(0, "", "", 0))
        val result = RootExecutionTarget("root-1", context(), executor).execute(emptyList())

        assertEquals(0, executor.callCount)
        assertEquals(-1, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
    }

    @Test
    fun `a non-null workingDir fails without calling the underlying executor`() {
        val executor = RecordingRootExecutor(true, RootExecutionResult.Success(0, "", "", 0))
        val result = RootExecutionTarget("root-1", context(), executor).execute(listOf("id"), workingDir = "/tmp")

        assertEquals(0, executor.callCount)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("working directory"))
    }

    @Test
    fun `a non-null env fails without calling the underlying executor`() {
        val executor = RecordingRootExecutor(true, RootExecutionResult.Success(0, "", "", 0))
        val result = RootExecutionTarget("root-1", context(), executor).execute(listOf("id"), env = mapOf("FOO" to "bar"))

        assertEquals(0, executor.callCount)
        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("environment"))
    }

    @Test
    fun `argv is split into executable and args, timeoutMs is forwarded`() {
        val executor = RecordingRootExecutor(true, RootExecutionResult.Success(0, "", "", 0))
        RootExecutionTarget("root-1", context(), executor).execute(listOf("id", "-u"), timeoutMs = 5_000)

        val command = executor.lastCommand!!
        assertEquals("id", command.executable)
        assertEquals(listOf("-u"), command.args)
        assertEquals(5_000, command.timeoutMillis)
    }

    @Test
    fun `a Success result maps exit code, stdout and stderr, never timed out, never verified`() {
        val executor = RecordingRootExecutor(true, RootExecutionResult.Success(exitCode = 0, stdout = "0", stderr = "", durationMillis = 5))
        val result = RootExecutionTarget("root-1", context(), executor).execute(listOf("id", "-u"))

        assertEquals(0, result.exitCode)
        assertEquals("0", result.stdout)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
        assertEquals(ExecutionTargetType.ANDROID, result.target)
    }

    @Test
    fun `a non-timeout Failure maps to exit code -1 with timedOut false`() {
        val executor = RecordingRootExecutor(true, RootExecutionResult.Failure("Failed to start 'su': not found"))
        val result = RootExecutionTarget("root-1", context(), executor).execute(listOf("id"))

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("not found"))
        assertFalse(result.timedOut)
    }

    @Test
    fun `a timeout Failure is reported as timedOut true`() {
        val executor = RecordingRootExecutor(true, RootExecutionResult.Failure("Command timed out after 30000ms"))
        val result = RootExecutionTarget("root-1", context(), executor).execute(listOf("sleep", "60"), timeoutMs = 30_000)

        assertEquals(-1, result.exitCode)
        assertTrue(result.timedOut)
    }
}
