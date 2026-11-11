package ai.droidcommand.llm.local

import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.StreamingLlmProvider
import java.io.IOException

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
 *
 * The conversation is fitted to the model's context window before it reaches the backend ([ContextFit]): a chat sends its
 * whole history each turn, and a small window would otherwise refuse every turn once the history grew past it. The oldest
 * turns are left out; a newest message that cannot fit on its own is refused with a message that says why.
 *
 * Before the first load the file's own GGUF metadata is read ([GgufMetadataReader]): a file [GgufCompatibility] can name
 * as not a chat model is refused with what to do instead, the window is never larger than the one the model was trained
 * with, and the RAM estimate uses the model's real cache cost. A file whose metadata cannot be read is left to
 * [ModelRepository.verify] and llama.cpp to judge. A load failure reports llama.cpp's own reason with the file's
 * architecture and GGUF version.
 */
class LocalLlmProvider(
    private val modelId: String,
    private val repository: ModelRepository,
    private val backend: InferenceBackend,
    override val config: LlmConfig = LlmConfig(provider = "local", model = modelId),
    private val memory: DeviceMemory? = null,
) : StreamingLlmProvider {
    private var loaded = false

    /** The file's own metadata, read once per load cycle. `null` inside means unreadable or not parseable. */
    private var inspected: Inspection? = null

    private class Inspection(val metadata: GgufMetadata?)

    override fun complete(request: LlmRequest): LlmResponse {
        val sb = StringBuilder()
        val r = stream(request) { sb.append(it) }
        return if (r is LlmResponse.Text) LlmResponse.Text(sb.toString()) else r
    }

    override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse = stream(request, { false }, onTextDelta)

    /**
     * As [stream], but stops when [cancelled] turns true: before the model is loaded, and after any token. What was
     * generated up to then is returned as [LlmResponse.Text] (possibly empty); the caller, which owns [cancelled],
     * decides what a stopped reply means. [requestStop] makes a stop take effect while the prompt is still being read.
     */
    @Synchronized
    fun stream(request: LlmRequest, cancelled: () -> Boolean, onTextDelta: (String) -> Unit): LlmResponse {
        if (request.tools.isNotEmpty()) return unsupported("tool calling")
        if (request.responseFormat != null) return unsupported("structured output")
        val meta = repository.get(modelId) ?: return LlmResponse.Error(LlmError.ModelUnavailable("Unknown model '$modelId'"))
        val gguf = inspect()
        if (gguf != null) GgufCompatibility.problem(gguf)?.let { return LlmResponse.Error(LlmError.ModelUnavailable(it)) }
        val contextTokens = GgufCompatibility.contextTokens(meta.contextTokens, gguf)
        // Decided before the model is loaded: loading a multi-gigabyte file only to refuse the message would be wasted work.
        val maxTokens = ContextFit.replyBudget(contextTokens, request.maxOutputTokens ?: config.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
        val prompt = ContextFit.fit(request.systemPrompt, request.messages, ContextFit.promptBudget(contextTokens, maxTokens))
        if (!prompt.fits) {
            return LlmResponse.Error(
                LlmError.ModelUnavailable(
                    "This message is too long for the on-device model: its context window is $contextTokens tokens. " +
                        "Shorten the message, or use a cloud provider.",
                ),
            )
        }
        if (cancelled()) return LlmResponse.Text("")
        try {
            if (!loaded) {
                val size = meta.sizeBytes
                if (memory != null && size != null) {
                    val snap = memory.snapshot()
                    val fit = RamFit.assess(size, contextTokens, snap.totalBytes, snap.availableBytes, gguf?.kvBytesPerToken())
                    if (fit.verdict == RamFitVerdict.TOO_BIG) {
                        return LlmResponse.Error(LlmError.ModelUnavailable(fit.reason))
                    }
                }
                when (val check = repository.verify(modelId)) {
                    is ModelCheck.Missing -> return LlmResponse.Error(
                        LlmError.ModelUnavailable(
                            "The model file is no longer there (${check.path}). Add the model again.",
                        ),
                    )
                    is ModelCheck.Mismatch -> return LlmResponse.Error(
                        LlmError.ModelUnavailable(
                            "The file for '${meta.displayName}' changed after it was added (SHA-256 mismatch, got ${check.actual}); " +
                                "refusing to load it. Remove the model and add it again.",
                        ),
                    )
                    is ModelCheck.Rejected -> return LlmResponse.Error(
                        LlmError.ModelUnavailable("Model '$modelId' failed its integrity check (${check.detail}); refusing to load"),
                    )
                    is ModelCheck.Verified -> {
                        if (cancelled()) return LlmResponse.Text("")
                        try {
                            backend.load(check.path, contextTokens)
                        } catch (e: InferenceException) {
                            return LlmResponse.Error(LlmError.ModelUnavailable(loadFailure(meta, gguf, e)))
                        }
                    }
                }
                loaded = true
            }
            val sb = StringBuilder()
            backend.generate(
                GenerationRequest(
                    systemPrompt = request.systemPrompt,
                    messages = prompt.messages,
                    maxTokens = maxTokens,
                    temperature = request.temperature ?: config.temperature,
                ),
            ) { delta ->
                if (cancelled()) {
                    false
                } else {
                    sb.append(delta)
                    onTextDelta(delta)
                    true
                }
            }
            return LlmResponse.Text(sb.toString())
        } catch (e: InferenceException) {
            return LlmResponse.Error(LlmError.ModelUnavailable(e.message ?: "inference failed"))
        }
    }

    /**
     * Asks a [stream] running on another thread to stop as soon as the backend can, even before its first token.
     * Not synchronized on purpose: [stream] holds the lock for the whole reply. The caller's `cancelled` must also be
     * true, or the stopped call is reported as an ordinary (short or empty) reply.
     */
    fun requestStop() = backend.requestStop()

    /** Releases the native context; the next call reloads (and re-verifies) the model. */
    @Synchronized
    fun close() {
        if (loaded) backend.unload()
        loaded = false
        inspected = null
    }

    private fun inspect(): GgufMetadata? {
        inspected?.let { return it.metadata }
        val file = repository.fileFor(modelId)
        val metadata = if (file != null && file.isFile) {
            try {
                (GgufMetadataReader.read(file) as? GgufMetadataResult.Valid)?.metadata
            } catch (_: IOException) {
                null
            }
        } else {
            null
        }
        inspected = Inspection(metadata)
        return metadata
    }

    private fun loadFailure(meta: ModelMetadata, gguf: GgufMetadata?, e: InferenceException): String {
        val what = gguf?.let { " (architecture '${it.architecture ?: "unknown"}', GGUF v${it.version})" }.orEmpty()
        val native = e.message?.takeIf { it.isNotBlank() } ?: "no reason given"
        return "Could not load '${meta.displayName}'$what: $native. " +
            "If the file was copied from a PC, copy it again in case it is incomplete; if it is a very new model, it may need " +
            "a newer llama.cpp than this app includes. A smaller quantization helps when memory is the limit."
    }

    private fun unsupported(what: String) =
        LlmResponse.Error(LlmError.ModelUnavailable("Local provider cannot serve requests that need $what"))

    companion object {
        const val DEFAULT_MAX_TOKENS = 512
    }
}

/**
 * The loaded on-device models of one process: at most one at a time, shared by every chat that uses it.
 *
 * Without this each `ChatSession` loaded its own copy of the model (the chat screen, an assist request and a recreated
 * screen would each hold several gigabytes), and nothing freed a copy whose owner simply went away. [provider] closes
 * any other loaded model first, so switching models frees the previous one before the next loads. Closing waits for a
 * reply that is being generated on that model.
 */
class LocalProviderPool(
    private val repository: ModelRepository,
    private val memory: DeviceMemory? = null,
    private val backendFactory: () -> InferenceBackend,
) {
    private val providers = LinkedHashMap<String, LocalLlmProvider>()

    /** The provider for [modelId], created on first use. Throws what [backendFactory] throws (e.g. no native library). */
    @Synchronized
    fun provider(modelId: String): LocalLlmProvider {
        providers.filterKeys { it != modelId }.values.forEach { it.close() }
        return providers.getOrPut(modelId) { LocalLlmProvider(modelId, repository, backendFactory(), memory = memory) }
    }

    /** Unloads [modelId] if it is loaded, e.g. before its file is deleted. */
    @Synchronized
    fun close(modelId: String) {
        providers.remove(modelId)?.close()
    }

    @Synchronized
    fun closeAll() {
        providers.values.forEach { it.close() }
        providers.clear()
    }
}
