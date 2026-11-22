package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner

/**
 * Closes the "remote request reaches a shell some other way" gap. Any tool that can run commands or gain privilege (TERMINAL/ROOT categories,
 * requiresRoot, or a shell/termux/root/shizuku/adb name) is refused when the request is REMOTE-initiated (fleet jobs, remote envelopes):
 * remote shell access exists only through VictorSuite's broker ([LiveFeatures]), which asks the phone's owner. Local (owner/AI) use keeps its
 * existing SecureToolExecutor policy. Fail-closed: an unknown tool name is refused for REMOTE too.
 */
class BrokerOnlyRunner(private val delegate: ToolRunner, private val registry: ToolRegistry) : ToolRunner {
    override fun run(toolName: String, input: Map<String, String>, retryPolicy: RetryPolicy, isCancelled: () -> Boolean, mode: AgentMode?, initiator: Initiator?, grantId: String?): ToolResult {
        if (initiator == Initiator.REMOTE && shellCapable(toolName)) {
            return ToolResult.Failure("'$toolName' can run commands; remote requests may only use VictorSuite's shell broker (owner-approved). Refused.")
        }
        return delegate.run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
    }

    private fun shellCapable(name: String): Boolean {
        val spec = runCatching { registry.get(name).spec }.getOrNull() ?: return true
        return spec.requiresRoot || spec.permissionCategory in setOf(PermissionCategory.TERMINAL, PermissionCategory.ROOT) ||
            Regex("shell|termux|root|shizuku|adb|exec|su\\b", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
}
