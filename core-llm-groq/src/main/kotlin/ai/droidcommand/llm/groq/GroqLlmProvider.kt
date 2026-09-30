package ai.droidcommand.llm.groq

import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.StreamingLlmProvider
import ai.droidcommand.llm.openai.OpenAiLlmProvider
import ai.droidcommand.remote.HttpTransport

/**
 * A [StreamingLlmProvider] backed by Groq's OpenAI-compatible Chat
 * Completions endpoint. Groq's API is a strict superset of the OpenAI
 * Chat Completions shape, so this provider delegates entirely to
 * [OpenAiLlmProvider] — the only difference is the base URL:
 * `https://api.groq.com/openai` instead of `https://api.openai.com`.
 *
 * A caller who sets [LlmConfig.endpoint] overrides the base URL (for
 * tests or a self-hosted Groq-compatible server); the `/openai` prefix
 * is part of Groq's own URL structure, not appended by this class, so
 * a custom endpoint must include it or omit it as needed for the target
 * server.
 */
class GroqLlmProvider(
    override val config: LlmConfig,
    transport: HttpTransport,
    retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : StreamingLlmProvider {
    private val delegate = OpenAiLlmProvider(
        config = LlmConfig(
            provider = config.provider,
            model = config.model,
            endpoint = config.endpoint ?: DEFAULT_OPENAI_COMPAT_URL,
            temperature = config.temperature,
            maxOutputTokens = config.maxOutputTokens,
            authToken = config.authToken,
        ),
        transport = transport,
        retryPolicy = retryPolicy,
        requireHttps = requireHttps,
    )

    override fun complete(request: LlmRequest): LlmResponse = delegate.complete(request)

    override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse =
        delegate.stream(request, onTextDelta)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.groq.com"

        /**
         * Groq's OpenAI-compatible endpoint lives under `/openai`, so the
         * delegate's [OpenAiLlmProvider] resolves `v1/chat/completions` to
         * `https://api.groq.com/openai/v1/chat/completions` correctly.
         */
        const val DEFAULT_OPENAI_COMPAT_URL = "$DEFAULT_BASE_URL/openai"
    }
}
