package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrokerOnlyRunnerTest {
    private fun tool(n: String, cat: PermissionCategory? = null, root: Boolean = false) = object : Tool {
        override val spec = ToolSpec(name = n, description = n, requiresRoot = root, permissionCategory = cat)
        override fun execute(input: Map<String, String>) = ToolResult.Success("ran $n")
    }
    private val reg = ToolRegistry().also { r -> listOf(tool("run_shell_command", PermissionCategory.TERMINAL), tool("termux_run"), tool("root_cmd", root = true), tool("hash_files"), tool("shizuku_exec")).forEach(r::register) }
    private val inner = object : ToolRunner {
        val calls = mutableListOf<String>()
        override fun run(toolName: String, input: Map<String, String>, retryPolicy: RetryPolicy, isCancelled: () -> Boolean, mode: AgentMode?, initiator: Initiator?, grantId: String?): ToolResult { calls += toolName; return ToolResult.Success("ok") }
    }
    private val g = BrokerOnlyRunner(inner, reg)

    @Test fun remoteCannotReachAnyShellCapableTool() {
        for (t in listOf("run_shell_command", "termux_run", "root_cmd", "shizuku_exec", "unknown_tool")) assertTrue(g.run(t, emptyMap(), initiator = Initiator.REMOTE) is ToolResult.Failure, t)
        assertTrue(inner.calls.isEmpty())
    }
    @Test fun remoteMayStillUseHarmlessTools() { assertTrue(g.run("hash_files", emptyMap(), initiator = Initiator.REMOTE) is ToolResult.Success); assertEquals(listOf("hash_files"), inner.calls) }
    @Test fun localOwnerPolicyIsUnchanged() { assertTrue(g.run("run_shell_command", emptyMap(), initiator = Initiator.DEVICE_OWNER) is ToolResult.Success); assertEquals(1, inner.calls.size) }
}
