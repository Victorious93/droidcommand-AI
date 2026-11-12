package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message

/** Compute backends the native llama.cpp build can be compiled with (Phase 2). */
enum class BackendKind { CPU, OPENCL, VULKAN }

/**
 * The seam the future llama.cpp JNI bridge implements. Nothing in this
 * module links native code: the real implementation needs the Android NDK,
 * which no build environment for this repo has had, so everything here is
 * exercised against fake backends only. Chat templating is deliberately
 * left to the backend (llama.cpp applies the model's own template), so
 * [GenerationRequest] carries structured messages, not a pre-rendered prompt.
 */
interface InferenceBackend {
    val kind: BackendKind

    /**
     * Whether [generate] samples under [GenerationRequest.grammar]. A backend that leaves this false is never given
     * a grammar, and [LocalLlmProvider] does not offer tools through it.
     */
    val supportsGrammar: Boolean get() = false

    /** Loads the GGUF at [modelPath]. Throws [InferenceException] on failure. */
    fun load(modelPath: String, contextTokens: Int)

    /** Streams generated text to [onToken]; stops early when [onToken] returns `false`. */
    fun generate(request: GenerationRequest, onToken: (String) -> Boolean)

    /**
     * Asks a [generate] that is running on another thread to return as soon as it can, including while it is still
     * reading the prompt (before the first token, where [generate]'s `onToken` cannot be consulted). Safe to call
     * from any thread at any time; a call with nothing running does nothing. The default does nothing, which leaves
     * stopping to `onToken` returning `false`.
     */
    fun requestStop() {}

    fun unload()
}

/**
 * [grammar], when set, is a GBNF grammar (root rule `root`) every generated token must satisfy. Only sent to a
 * backend whose [InferenceBackend.supportsGrammar] is true.
 */
data class GenerationRequest
    @JvmOverloads
    constructor(
        val systemPrompt: String?,
        val messages: List<Message>,
        val maxTokens: Int,
        val temperature: Double?,
        val grammar: String? = null,
    )

class InferenceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
