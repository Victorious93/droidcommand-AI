package ai.droidcommand.termux

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ToolScriptedTermuxExecutor(private val result: TermuxExecutionResult) : TermuxExecutor {
    var lastCommand: TermuxCommand? = null
        private set

    override fun isAvailable(): Boolean = true

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        lastCommand = command
        return result
    }
}

class TermuxToolTest {
    @Test
    fun `spec declares SecurityLevel SENSITIVE and PermissionCategory TERMINAL, never ROOT`() {
        val spec = TermuxTool(NullTermuxExecutor()).spec
        assertEquals(false, spec.requiresRoot)
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertEquals(PermissionCategory.TERMINAL, spec.permissionCategory)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `fails without executing when 'executable' is missing`() {
        val executor = ToolScriptedTermuxExecutor(TermuxExecutionResult.Success(0, "", "", 0))
        val result = TermuxTool(executor).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
    }

    @Test
    fun `a zero exit code becomes ToolResult Success carrying stdout`() {
        val executor = ToolScriptedTermuxExecutor(TermuxExecutionResult.Success(0, "hello", "", 5))
        val result = assertIs<ToolResult.Success>(TermuxTool(executor).execute(mapOf("executable" to "echo", "args" to "hello")))
        assertEquals("hello", result.output)
    }

    @Test
    fun `a non-zero exit code becomes ToolResult Failure`() {
        val executor = ToolScriptedTermuxExecutor(TermuxExecutionResult.Success(1, "", "not found", 5))
        val result = assertIs<ToolResult.Failure>(TermuxTool(executor).execute(mapOf("executable" to "nope")))
        assertTrue(result.reason.contains("not found"))
    }

    @Test
    fun `parses space-separated args and passes them to the executor`() {
        val executor = ToolScriptedTermuxExecutor(TermuxExecutionResult.Success(0, "", "", 0))
        TermuxTool(executor).execute(mapOf("executable" to "pkg", "args" to "install python"))
        assertEquals(listOf("install", "python"), executor.lastCommand?.args)
    }

    @Test
    fun `passes workingDirectory through when provided, mirroring ShellTool`() {
        val executor = ToolScriptedTermuxExecutor(TermuxExecutionResult.Success(0, "", "", 0))
        TermuxTool(executor).execute(mapOf("executable" to "pwd", "workingDirectory" to "/data/data/com.termux/files/home"))
        assertEquals("/data/data/com.termux/files/home", executor.lastCommand?.workingDirectory)
    }

    @Test
    fun `surfaces an executor-level failure directly`() {
        val executor = ToolScriptedTermuxExecutor(TermuxExecutionResult.Failure("no real Termux backend is configured"))
        val result = assertIs<ToolResult.Failure>(TermuxTool(executor).execute(mapOf("executable" to "echo")))
        assertEquals("no real Termux backend is configured", result.reason)
    }
}
