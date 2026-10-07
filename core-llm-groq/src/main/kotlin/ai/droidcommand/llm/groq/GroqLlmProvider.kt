package ai.droidcommand.llm.groq

import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.StreamingLlmProvider
import ai.droidcommand.llm.openai.OpenAiLlmProvider
import ai.droidcommand.remote.HttpTransport

/**
 * Groq serves an OpenAI-compatible Chat Completions API under the
 * `/openai` path prefix (`https://api.groq.com/openai/v1/chat/completions`),
 * so this provider is [OpenAiLlmProvider] with only the default base URL
 * changed — no wire types, parsing, streaming or error classification are
 * duplicated. Delegation (not subclassing) keeps `OpenAiLlmProvider` final.
 *
 * Unlike a local OpenAI-compatible server, Groq's hosted API always needs
 * a key; a missing one is not rejected up front here — the server's 401
 * surfaces as `LlmError.Authentication`, the same path a wrong key takes.
 *
 * Not verified against the live Groq API in this repository (tests use a
 * local mock server only, per the Phase 1 acceptance rule); the base URL
 * and path layout are from Groq's public documentation.
 */
class GroqLlmProvider(
    config: LlmConfig,
    transport: HttpTransport,
    retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : StreamingLlmProvider {
    override val config: LlmConfig = LlmConfig(
        provider = config.provider,
        model = config.model,
        endpoint = config.endpoint ?: DEFAULT_BASE_URL,
        temperature = config.temperature,
        maxOutputTokens = config.maxOutputTokens,
        authToken = config.authToken,
    )

    private val delegate = OpenAiLlmProvider(this.config, transport, retryPolicy, requireHttps)

    override fun complete(request: LlmRequest): LlmResponse = delegate.complete(request)

    override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse =
        delegate.stream(request, onTextDelta)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai"
    }
}
