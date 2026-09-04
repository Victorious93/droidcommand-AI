package ai.droidforge.shell

import ai.droidforge.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class ScriptedShellExecutor(private val result: ShellExecutionResult) : ShellExecutor {
    var lastCommand: ShellCommand? = null
        private set

    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        lastCommand = command
        return result
    }
}

class ShellToolTest {
    @Test
    fun `fails without executing when 'executable' is missing`() {
        val executor = ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        val result = ShellTool(executor).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, (executor).lastCommand)
    }

    @Test
    fun `a zero exit code becomes ToolResult Success carrying stdout`() {
        val executor = ScriptedShellExecutor(ShellExecutionResult.Success(0, "output text", "", 5))
        val result = assertIs<ToolResult.Success>(ShellTool(executor).execute(mapOf("executable" to "echo")))
        assertEquals("output text", result.output)
    }

    @Test
    fun `a non-zero exit code becomes ToolResult Failure`() {
        val executor = ScriptedShellExecutor(ShellExecutionResult.Success(1, "", "boom", 5))
        val result = assertIs<ToolResult.Failure>(ShellTool(executor).execute(mapOf("executable" to "false")))
        assertEquals(true, result.reason.contains("boom"))
    }

    @Test
    fun `parses space-separated args`() {
        val executor = ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        ShellTool(executor).execute(mapOf("executable" to "echo", "args" to "a b c"))
        assertEquals(listOf("a", "b", "c"), executor.lastCommand?.args)
    }

    @Test
    fun `passes workingDirectory through when provided`() {
        val executor = ScriptedShellExecutor(ShellExecutionResult.Success(0, "", "", 0))
        ShellTool(executor).execute(mapOf("executable" to "pwd", "workingDirectory" to "/tmp"))
        assertEquals("/tmp", executor.lastCommand?.workingDirectory)
    }

    @Test
    fun `surfaces an executor-level failure directly`() {
        val executor = ScriptedShellExecutor(ShellExecutionResult.Failure("not allowed"))
        val result = assertIs<ToolResult.Failure>(ShellTool(executor).execute(mapOf("executable" to "rm")))
        assertEquals("not allowed", result.reason)
    }
}
