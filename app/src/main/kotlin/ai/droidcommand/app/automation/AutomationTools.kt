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
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import ai.droidcommand.shell.ShellTool
import ai.droidcommand.termux.TermuxCommand
import ai.droidcommand.termux.TermuxExecutionResult
import ai.droidcommand.termux.TermuxExecutor

/**
 * Sends one prompt to a cloud provider for an automation key. It is gated like the shell tool: [SecurityLevel.SENSITIVE]
 * asks for approval on every run, and [PermissionCategory.NETWORK] must be granted. [allowedModes] is empty, so the
 * Pilot and Forge planners never offer it. Only a saved key can run it.
 */
class AiPromptTool(
    private val client: CloudPromptClient,
    private val modelFor: (CloudProviderSpec) -> String = { it.defaultModel },
    /** Non-null when the owner's inference policy does not allow a cloud request: the text to return instead of sending. */
    private val policyRefusal: (CloudProviderSpec) -> String? = { null },
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
        policyRefusal(provider)?.let { return ToolResult.Failure(it) }
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
 * The shell executor for automation keys. On a phone every product shell action goes through VictorSuite
 * (VictorSuite docs/SHELL_ROUTING.md), so this never starts a process in DroidCommand's own sandbox: it checks the
 * owner's allowlist (read on every run, empty by default, so nothing runs) and then hands the argv to VictorSuite through
 * [TermuxExecutor] (`com.termux.RUN_COMMAND` with a pending-intent result). A working directory is refused because
 * DroidCommand cannot vouch for a path inside VictorSuite's sandbox. Output goes back to the caller only and is not logged.
 */
class AllowlistShellExecutor(
    private val termux: TermuxExecutor,
    private val allowedExecutables: () -> Set<String>,
) : ShellExecutor {
    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        if (command.executable !in allowedExecutables()) {
            return ShellExecutionResult.Failure("Executable '${command.executable}' is not in the allowed executable list")
        }
        if (command.workingDirectory != null) {
            return ShellExecutionResult.Failure("A working directory is not supported for automation commands run in VictorSuite")
        }
        if (!termux.isAvailable()) {
            return ShellExecutionResult.Failure(UNAVAILABLE)
        }
        val request = TermuxCommand(command.executable, command.args, null, command.environment, command.timeoutMillis)
        return when (val result = termux.execute(request, isCancelled)) {
            is TermuxExecutionResult.Success ->
                ShellExecutionResult.Success(result.exitCode, result.stdout, result.stderr, result.durationMillis)
            is TermuxExecutionResult.Failure -> ShellExecutionResult.Failure(result.reason, result.cause)
        }
    }

    companion object {
        const val UNAVAILABLE =
            "VictorSuite is not installed or DroidCommand has not been granted RUN_COMMAND, so the command did not run"
    }
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
