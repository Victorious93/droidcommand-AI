package ai.droidcommand.app.automation

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.CloudPromptClient
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import ai.droidcommand.shell.ShellTool

/**
 * Sends one prompt to a cloud provider for an automation key. It is gated like the shell tool: [SecurityLevel.SENSITIVE]
 * asks for approval on every run, and [PermissionCategory.NETWORK] must be granted. [allowedModes] is empty, so the
 * Pilot and Forge planners never offer it. Only a saved key can run it.
 */
class AiPromptTool(
    private val client: CloudPromptClient,
    private val modelFor: (CloudProviderSpec) -> String = { it.defaultModel },
) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Sends one prompt to a cloud AI provider and returns its reply",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.NETWORK,
        allowedModes = emptySet(),
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val providerId = input["provider"] ?: return ToolResult.Failure("Missing required input 'provider'")
        val prompt = input["prompt"] ?: return ToolResult.Failure("Missing required input 'prompt'")
        val provider = CloudProviderCatalog.byId(providerId) ?: return ToolResult.Failure("Unknown AI provider '$providerId'")
        return when (val result = client.ask(provider, modelFor(provider), prompt)) {
            is ChatResult.Reply -> ToolResult.Success(result.text)
            is ChatResult.Failure -> ToolResult.Failure(result.message)
        }
    }

    companion object {
        const val NAME = "ask_ai"
    }
}

/**
 * The shell executor for automation keys. It reads the allowlist on every run, so an edit takes effect at once, and it
 * builds a [ProcessBuilderShellExecutor] with that list, so the executor's own policy check still applies. With an empty
 * list nothing runs.
 */
class AllowlistShellExecutor(private val allowedExecutables: () -> Set<String>) : ShellExecutor {
    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult =
        ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = allowedExecutables())).execute(command, isCancelled)
}

/**
 * [ShellTool] registered for automation keys only. [allowedModes] is empty, so the planners never see it. Every run still
 * asks for approval, because the tool keeps its [SecurityLevel.SENSITIVE] level.
 */
class AutomationShellTool(executor: ShellExecutor) : Tool {
    private val delegate = ShellTool(executor)

    override val spec: ToolSpec = delegate.spec.copy(allowedModes = emptySet())

    override fun execute(input: Map<String, String>): ToolResult = delegate.execute(input)

    companion object {
        /** The name [ShellTool] registers under. A test checks that it has not drifted. */
        const val NAME = "run_shell_command"
    }
}
