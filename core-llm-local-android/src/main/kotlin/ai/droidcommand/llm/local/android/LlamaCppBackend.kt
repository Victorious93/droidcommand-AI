package ai.droidcommand.llm.local.android

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.InferenceException
import ai.droidcommand.llm.local.Utf8StreamDecoder

/**
 * [InferenceBackend] backed by the llama.cpp JNI shim ([llama_jni.cpp]). CPU only (matches the
 * CMakeLists.txt CPU-only build) — OpenCL/Vulkan is Phase D and will need their own [BackendKind]
 * implementations, not a flag on this one, since llama.cpp selects a backend at model-load time
 * via `llama_model_params`/ggml backend registration, not per call.
 *
 * Calls into this class are not internally synchronized: [ai.droidcommand.llm.local.LocalLlmProvider]
 * already serializes `load`/`generate`/`unload` (a loaded native context is not safe to share), and
 * this class has no other caller.
 */
class LlamaCppBackend : InferenceBackend {
    override val kind: BackendKind = BackendKind.CPU

    @Volatile
    private var handle: Long = 0L

    override fun load(modelPath: String, contextTokens: Int) {
        check(handle == 0L) { "load() called on an already-loaded backend; call unload() first" }
        handle = nativeLoad(modelPath, contextTokens)
        if (handle == 0L) {
            // nativeLoad throws InferenceException on failure before returning 0, so reaching
            // here with a zero handle and no pending exception would itself be a native bug.
            throw InferenceException("llama.cpp returned no context and raised no exception")
        }
    }

    override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
        val h = handle
        check(h != 0L) { "generate() called before load()" }
        val turns = request.messages.filter { it.role != Role.SYSTEM }
        val roles = turns.map { roleName(it.role) }.toTypedArray()
        val contents = turns.map(Message::content).toTypedArray()
        val emit = onToken
        val decoder = Utf8StreamDecoder()
        var stopped = false
        nativeGenerate(
            h,
            request.systemPrompt,
            roles,
            contents,
            request.maxTokens,
            request.temperature ?: DEFAULT_TEMPERATURE,
            object : TokenSink {
                override fun onToken(bytes: ByteArray): Boolean {
                    // A token may end mid-character; only complete characters are forwarded.
                    val text = decoder.push(bytes)
                    if (text.isEmpty()) return true
                    val keepGoing = emit(text)
                    if (!keepGoing) stopped = true
                    return keepGoing
                }
            },
        )
        if (!stopped) {
            val tail = decoder.flush()
            if (tail.isNotEmpty()) emit(tail)
        }
    }

    override fun unload() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        nativeUnload(h)
    }

    /**
     * llama.cpp's built-in chat templates render "system"/"user"/"assistant" turns; [Role.TOOL]
     * (a past tool result in conversation history — [ai.droidcommand.llm.local.LocalLlmProvider]
     * already refuses any request that itself asks for tool calling) has no standard chat-template
     * role, so it is passed through as "tool" best-effort: templates that define a tool role
     * render it, templates that don't will render it however they render an unrecognized role.
     * Not verified against a real template — revisit once Phase E can test this on a device.
     */
    private fun roleName(role: Role): String =
        when (role) {
            Role.SYSTEM -> "system"
            Role.USER -> "user"
            Role.ASSISTANT -> "assistant"
            Role.TOOL -> "tool"
        }

    private external fun nativeLoad(modelPath: String, contextTokens: Int): Long

    private external fun nativeGenerate(
        handle: Long,
        systemPrompt: String?,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Double,
        sink: TokenSink,
    )

    private external fun nativeUnload(handle: Long)

    companion object {
        private const val DEFAULT_TEMPERATURE = 0.0

        init {
            System.loadLibrary("dca_llama_jni")
        }
    }
}

/**
 * Called from native code (`llama_jni.cpp`'s `CallBooleanMethod(sink, onToken, ...)`) once per
 * generated token, with the token's raw bytes. A dedicated interface rather than `(String) -> Boolean` directly: Kotlin
 * function types compile to `kotlin.jvm.functions.Function1` with a boxed `Object invoke(Object)`,
 * which would need JNI-side unboxing; this keeps the native call signature primitive.
 */
interface TokenSink {
    /** One token's raw UTF-8 bytes, which may end in the middle of a character. Return `false` to stop. */
    fun onToken(bytes: ByteArray): Boolean
}
