package ai.droidcommand.llm.local

import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.StreamingLlmProvider

/**
 * [StreamingLlmProvider] for [ai.droidcommand.llm.ProviderType.LOCAL] models.
 * Loads lazily on first use, and only from a path [ModelRepository.verify]
 * has just reported [ModelCheck.Verified]; a missing or tampered model
 * yields [LlmError.ModelUnavailable] and never reaches the backend.
 *
 * Tool calls and structured output are not supported (llama.cpp grammar /
 * tool parsing is not implemented): requests asking for either are refused
 * rather than silently ignored, with [LlmError.ModelUnavailable] — NOT
 * [LlmError.InvalidResponse]. The distinction matters to
 * [ai.droidcommand.llm.ModelRouter]: it falls back to the next provider on
 * `ModelUnavailable` but never on `InvalidResponse`, and the agent planner
 * always sends tools, so a local-first router would otherwise fail every
 * planning request instead of using its cloud provider. Calls are
 * serialized, since a loaded native context is not safe to share.
 */
class LocalLlmProvider(
    private val modelId: String,
    private val repository: ModelRepository,
    private val backend: InferenceBackend,
    override val config: LlmConfig = LlmConfig(provider = "local", model = modelId),
) : StreamingLlmProvider {
    private var loaded = false

    override fun complete(request: LlmRequest): LlmResponse {
        val sb = StringBuilder()
        val r = stream(request) { sb.append(it) }
        return if (r is LlmResponse.Text) LlmResponse.Text(sb.toString()) else r
    }

    @Synchronized
    override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse {
        if (request.tools.isNotEmpty()) return unsupported("tool calling")
        if (request.responseFormat != null) return unsupported("structured output")
        val meta = repository.get(modelId) ?: return LlmResponse.Error(LlmError.ModelUnavailable("Unknown model '$modelId'"))
        try {
            if (!loaded) {
                when (val check = repository.verify(modelId)) {
                    is ModelCheck.Missing -> return LlmResponse.Error(LlmError.ModelUnavailable("Model file not found: ${check.path}"))
                    is ModelCheck.Mismatch -> return LlmResponse.Error(
                        LlmError.ModelUnavailable("SHA-256 mismatch for '$modelId' (got ${check.actual}); refusing to load"),
                    )
                    is ModelCheck.Verified -> backend.load(check.path, meta.contextTokens)
                }
                loaded = true
            }
            val sb = StringBuilder()
            backend.generate(
                GenerationRequest(
                    systemPrompt = request.systemPrompt,
                    messages = request.messages,
                    maxTokens = request.maxOutputTokens ?: config.maxOutputTokens ?: DEFAULT_MAX_TOKENS,
                    temperature = request.temperature ?: config.temperature,
                ),
            ) { delta ->
                sb.append(delta)
                onTextDelta(delta)
                true
            }
            return LlmResponse.Text(sb.toString())
        } catch (e: InferenceException) {
            return LlmResponse.Error(LlmError.ModelUnavailable(e.message ?: "inference failed"))
        }
    }

    /** Releases the native context; the next call reloads (and re-verifies) the model. */
    @Synchronized
    fun close() {
        if (loaded) backend.unload()
        loaded = false
    }

    private fun unsupported(what: String) =
        LlmResponse.Error(LlmError.ModelUnavailable("Local provider cannot serve requests that need $what"))

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
    }
}
