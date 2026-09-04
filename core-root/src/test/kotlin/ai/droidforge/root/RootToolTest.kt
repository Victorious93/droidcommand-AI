package ai.droidforge.root

import ai.droidforge.agent.SecurityLevel
import ai.droidforge.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ToolScriptedRootExecutor(private val result: RootExecutionResult) : RootExecutor {
    var lastCommand: RootCommand? = null
        private set

    override fun isRootAvailable(): Boolean = true

    override fun execute(command: RootCommand, isCancelled: () -> Boolean): RootExecutionResult {
        lastCommand = command
        return result
    }
}

class RootToolTest {
    @Test
    fun `spec declares requiresRoot and SecurityLevel ROOT`() {
        val spec = RootTool(NullRootExecutor()).spec
        assertTrue(spec.requiresRoot)
        assertEquals(SecurityLevel.ROOT, spec.securityLevel)
    }

    @Test
    fun `fails without executing when 'executable' is missing`() {
        val executor = ToolScriptedRootExecutor(RootExecutionResult.Success(0, "", "", 0))
        val result = RootTool(executor).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun `a zero exit code becomes ToolResult Success carrying stdout`() {
        val executor = ToolScriptedRootExecutor(RootExecutionResult.Success(0, "uid=0(root)", "", 5))
        val result = assertIs<ToolResult.Success>(RootTool(executor).execute(mapOf("executable" to "id")))
        assertEquals("uid=0(root)", result.output)
    }

    @Test
    fun `a non-zero exit code becomes ToolResult Failure`() {
        val executor = ToolScriptedRootExecutor(RootExecutionResult.Success(1, "", "permission denied", 5))
        val result = assertIs<ToolResult.Failure>(RootTool(executor).execute(mapOf("executable" to "rm")))
        assertTrue(result.reason.contains("permission denied"))
    }

    @Test
    fun `parses space-separated args and passes them to the executor`() {
        val executor = ToolScriptedRootExecutor(RootExecutionResult.Success(0, "", "", 0))
        RootTool(executor).execute(mapOf("executable" to "rm", "args" to "-rf /data/local/tmp/x"))
        assertEquals(listOf("-rf", "/data/local/tmp/x"), executor.lastCommand?.args)
    }

    @Test
    fun `surfaces an executor-level failure directly`() {
        val executor = ToolScriptedRootExecutor(RootExecutionResult.Failure("no real rooted device"))
        val result = assertIs<ToolResult.Failure>(RootTool(executor).execute(mapOf("executable" to "id")))
        assertEquals("no real rooted device", result.reason)
    }
}
