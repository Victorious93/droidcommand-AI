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

    /** Loads the GGUF at [modelPath]. Throws [InferenceException] on failure. */
    fun load(modelPath: String, contextTokens: Int)

    /** Streams generated text to [onToken]; stops early when [onToken] returns `false`. */
    fun generate(request: GenerationRequest, onToken: (String) -> Boolean)

    fun unload()
}

data class GenerationRequest(
    val systemPrompt: String?,
    val messages: List<Message>,
    val maxTokens: Int,
    val temperature: Double?,
)

class InferenceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
