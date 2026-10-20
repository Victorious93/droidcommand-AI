package ai.droidcommand.app.automation

import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.factory.CloudPromptClient
import ai.droidcommand.llm.factory.CloudProviderCatalog
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor

/** Provider fake: answers each request with [reply] and keeps every request it was given. */
internal class ScriptedProvider(private val reply: (LlmRequest) -> LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "anthropic", model = "test-model", authToken = { null })
    val requests = mutableListOf<LlmRequest>()

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return reply(request)
    }
}

/** A transport that must never be used, because every test supplies its own provider. */
internal object UnusedTransport : HttpTransport {
    override fun send(request: HttpRequestSpec): HttpResponseSpec = error("tests supply their own provider")
}

/** Shell fake: records each command and returns [result]. It never starts a process. */
internal class FakeShell(
    private val result: ShellExecutionResult = ShellExecutionResult.Success(0, "out", "", 1),
) : ShellExecutor {
    val commands = mutableListOf<ShellCommand>()

    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        commands += command
        return result
    }
}

/** Approval fake: records which tool was asked about, and answers [answer]. */
internal class RecordingApproval(private val answer: Boolean) : ApprovalPrompt {
    val asked = mutableListOf<String>()

    override fun requestApproval(reason: String): Boolean = answer

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
        asked += toolName
        return answer
    }
}

/** A client for the anthropic provider, with a test key stored and [provider] answering every call. */
internal fun clientFor(provider: LlmProvider): CloudPromptClient {
    val vault = InMemorySecretsVault()
    vault.putSecret(CloudProviderCatalog.byId("anthropic")!!.secretId, "sk-test-0123456789")
    return CloudPromptClient(vault, UnusedTransport, providerFor = { _, _ -> provider })
}
