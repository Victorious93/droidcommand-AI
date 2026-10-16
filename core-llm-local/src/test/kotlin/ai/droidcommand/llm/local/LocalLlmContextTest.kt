package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class RecordingBackend : InferenceBackend {
    override val kind = BackendKind.CPU
    var loads = 0
    var generations = 0
    var lastRequest: GenerationRequest? = null

    override fun load(modelPath: String, contextTokens: Int) {
        loads++
    }

    override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
        generations++
        lastRequest = request
        onToken("ok")
    }

    override fun unload() {}
}

/** The on-device provider keeps a growing chat inside the model's context window instead of failing every later turn. */
class LocalLlmContextTest {
    private val dir: File = Files.createTempDirectory("models").toFile().also { it.deleteOnExit() }
    private val content = "tiny-model-bytes".toByteArray()
    private val sha = ModelRepository.sha256Hex(content.inputStream())

    private fun provider(contextTokens: Int, backend: InferenceBackend): LocalLlmProvider {
        File(dir, "m.gguf").writeBytes(content)
        val repository = ModelRepository(dir).also { it.register(ModelMetadata("m", "M", "m.gguf", sha, contextTokens)) }
        return LocalLlmProvider("m", repository, backend)
    }

    // 600 ASCII characters estimate to 200 tokens, plus 12 for the template markers.
    private val longTurn = "word ".repeat(120)

    @Test
    fun `a long history is trimmed before the backend sees it`() {
        val backend = RecordingBackend()
        // 1,300-token window: 512 for the reply and 32 for the template leave 756 for the prompt. With the system text that
        // is room for three of these turns (about 214 tokens each), not five.
        val history = listOf(
            Message(Role.USER, "first $longTurn"),
            Message(Role.ASSISTANT, "second $longTurn"),
            Message(Role.USER, "third $longTurn"),
            Message(Role.ASSISTANT, "fourth $longTurn"),
            Message(Role.USER, "fifth $longTurn"),
        )

        val response = provider(1_300, backend).complete(LlmRequest("sys", history))

        assertIs<LlmResponse.Text>(response)
        val sent = backend.lastRequest!!
        assertEquals("sys", sent.systemPrompt)
        assertEquals(history.takeLast(3), sent.messages, "the oldest two turns were left out; the newest message is kept")
        assertEquals(512, sent.maxTokens)
    }

    @Test
    fun `a conversation that fits reaches the backend untouched`() {
        val backend = RecordingBackend()
        val history = listOf(Message(Role.USER, "hi"), Message(Role.ASSISTANT, "hello"), Message(Role.USER, "again"))

        provider(2_048, backend).complete(LlmRequest("sys", history))

        assertEquals(history, backend.lastRequest!!.messages)
    }

    @Test
    fun `a message too big for the window is refused with a reason and the model is never loaded`() {
        val backend = RecordingBackend()

        val response = provider(1_024, backend).complete(LlmRequest(null, listOf(Message(Role.USER, "x".repeat(20_000)))))

        val error = assertIs<LlmResponse.Error>(response).error
        assertIs<LlmError.ModelUnavailable>(error)
        assertTrue(error.message.contains("1024 tokens"), error.message)
        assertEquals(0, backend.loads)
        assertEquals(0, backend.generations)
    }

    @Test
    fun `a huge reply allowance is capped so the prompt keeps room`() {
        val backend = RecordingBackend()

        provider(2_048, backend).complete(LlmRequest(null, listOf(Message(Role.USER, "hi")), maxOutputTokens = 4_096))

        assertEquals(1_024, backend.lastRequest!!.maxTokens)
    }
}
