package ai.droidcommand.shell

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.agent.ExecutionContext
import ai.droidcommand.agent.ExecutionTargetType
import ai.droidcommand.agent.PrivilegeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class RecordingShellExecutor(private val response: ShellExecutionResult) : ShellExecutor {
    var invocations = 0
    var lastCommand: ShellCommand? = null

    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        invocations++
        lastCommand = command
        return response
    }
}

class ShellExecutorExecutionTargetTest {
    private fun context(workingDir: String = "/default", environment: Map<String, String> = mapOf("DEFAULT" to "1")) = ExecutionContext(
        workingDir = workingDir,
        user = "tester",
        environment = environment,
        privilegeLevel = PrivilegeLevel.USER,
    )

    @Test
    fun `a real echo command runs end-to-end through the real ProcessBuilderShellExecutor`() {
        val realDir = System.getProperty("java.io.tmpdir")
        val shellExecutor = ProcessBuilderShellExecutor(
            ShellSecurityPolicy(allowedExecutables = setOf("echo"), allowedWorkingDirectories = listOf(realDir)),
        )
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context(workingDir = realDir))

        val result = target.execute(listOf("echo", "hello", "world"))

        assertEquals(0, result.exitCode)
        assertEquals("hello world", result.stdout.trim())
        assertFalse(result.timedOut)
        assertFalse(result.verified)
        assertEquals(ExecutionTargetType.LOCAL_PC, result.target)
    }

    @Test
    fun `an empty argv is rejected without ever invoking the executor`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context())

        val result = target.execute(emptyList())

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.isNotBlank())
        assertEquals(0, shellExecutor.invocations)
    }

    @Test
    fun `argv head becomes the executable and the tail becomes args`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context())

        target.execute(listOf("ls", "-la", "/tmp"))

        assertEquals("ls", shellExecutor.lastCommand!!.executable)
        assertEquals(listOf("-la", "/tmp"), shellExecutor.lastCommand!!.args)
    }

    @Test
    fun `a per-call workingDir and env override the context defaults`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context())

        target.execute(listOf("pwd"), workingDir = "/override", env = mapOf("OVERRIDE" to "1"))

        assertEquals("/override", shellExecutor.lastCommand!!.workingDirectory)
        assertEquals(mapOf("OVERRIDE" to "1"), shellExecutor.lastCommand!!.environment)
    }

    @Test
    fun `a null per-call workingDir and env fall back to the context defaults`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context(workingDir = "/default", environment = mapOf("DEFAULT" to "1")))

        target.execute(listOf("pwd"))

        assertEquals("/default", shellExecutor.lastCommand!!.workingDirectory)
        assertEquals(mapOf("DEFAULT" to "1"), shellExecutor.lastCommand!!.environment)
    }

    @Test
    fun `timeoutMs is forwarded to ShellCommand's timeoutMillis`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context())

        target.execute(listOf("sleep", "1"), timeoutMs = 5_000)

        assertEquals(5_000, shellExecutor.lastCommand!!.timeoutMillis)
    }

    @Test
    fun `a ShellExecutionResult Failure maps to exitCode -1 with the reason as stderr`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Failure("something went wrong"))
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context())

        val result = target.execute(listOf("rm"))

        assertEquals(-1, result.exitCode)
        assertEquals("something went wrong", result.stderr)
        assertFalse(result.timedOut)
        assertFalse(result.verified)
    }

    @Test
    fun `isHealthy is unconditionally true`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val target = ShellExecutorExecutionTarget("local", shellExecutor, context())

        assertTrue(target.isHealthy())
    }

    @Test
    fun `id, type, context and availableCapabilities are exposed as constructed`() {
        val shellExecutor = RecordingShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val ctx = context()
        val capability = CapabilityId("shell.exec")
        val target = ShellExecutorExecutionTarget("local-1", shellExecutor, ctx, availableCapabilities = setOf(capability))

        assertEquals("local-1", target.id)
        assertEquals(ExecutionTargetType.LOCAL_PC, target.type)
        assertEquals(ctx, target.context)
        assertEquals(setOf(capability), target.availableCapabilities)
    }
}
