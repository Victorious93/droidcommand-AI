package ai.droidcommand.llm.routing

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.llm.factory.LocalProviderResources
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.llm.pchost.InMemoryPcHostSettingsStore
import ai.droidcommand.remote.HttpUrlConnectionTransport
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A helper bot's tools on the on-device model under "This device only". FAKE backend that replays scripted text (no
 * llama.cpp); the model file is a real GGUF header whose chat template declares tool calls, which is what the
 * session reads to decide. The transport fails the test on any request.
 */
class RoutedDeviceToolsTest {
    private class Scripted(private val replies: MutableList<String>) : InferenceBackend {
        override val kind = BackendKind.CPU
        override val supportsGrammar = true
        val seen = mutableListOf<GenerationRequest>()

        override fun load(modelPath: String, contextTokens: Int) = Unit

        override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
            seen += request
            for (piece in replies.removeAt(0).chunked(7)) if (!onToken(piece)) return
        }

        override fun unload() = Unit
    }

    /** A GGUF header and key/value section with a tool-declaring chat template: enough for the metadata reader, not loadable. */
    private fun gguf(): ByteArray {
        val out = ByteArrayOutputStream()

        fun le(n: Int, v: Long) = repeat(n) { out.write(((v ushr (8 * it)) and 0xFF).toInt()) }

        fun str(s: String) {
            val b = s.toByteArray()
            le(8, b.size.toLong())
            out.write(b)
        }

        fun entry(key: String, value: String) {
            str(key)
            le(4, 8)
            str(value)
        }
        out.write("GGUF".toByteArray())
        le(4, 3)
        le(8, 1)
        le(8, 3)
        entry("general.architecture", "qwen2")
        entry("tokenizer.ggml.model", "gpt2")
        entry("tokenizer.chat_template", "{% if tools %}<tools></tools>{% endif %}<tool_call>{{ m }}</tool_call><|im_start|>")
        return out.toByteArray()
    }

    @Test
    fun `this device only - a tool-capable on-device model runs the bot's tool once through the secure path, with no network`() {
        val dir: File = Files.createTempDirectory("routed-device-tools").toFile().also { it.deleteOnExit() }
        val bytes = gguf()
        File(dir, "m.gguf").writeBytes(bytes)
        val repository = ModelRepository(dir).also {
            it.register(ModelMetadata("qwen", "Qwen", "m.gguf", ModelRepository.sha256Hex(bytes.inputStream()), 4096))
        }
        val backend = Scripted(mutableListOf("<tool_call>\n{\"name\": \"note_add\", \"arguments\": {\"title\": \"milk\"}}\n</tool_call>", "Added milk."))
        val transport = RecordingTransport(HttpUrlConnectionTransport()) { true }
        val vault = InMemorySecretsVault()
        val resolver = RouteResolver(
            vault,
            InMemoryInferenceSettingsStore(InferenceSettings(InferencePolicy.DEVICE_ONLY, deviceModelId = "qwen")),
            InMemoryPcHostSettingsStore(),
            deviceModels = { repository },
            cloudChoice = { null },
            inspectPc = { _, _ -> error("the PC must not be asked under this policy") },
        )
        val session = ChatSession(
            vault,
            InMemoryConversationStore(),
            transport = transport,
            providerFor = { _, _ -> error("a cloud provider must not be built") },
            local = LocalProviderResources(repository) { backend },
            router = resolver,
        )
        val tools = SecureTools(setOf("note_add"))
        val add = tools.add(SecureTools.Counting("note_add"))
        session.setToolAccess(tools.runner, tools.specs("note_add"))
        assertEquals(true, session.localToolSupport("qwen")?.supported, session.localToolSupport("qwen")?.reason)

        val reply = assertIs<ChatResult.Reply>(session.sendRouted("add milk to my notes"))

        assertEquals("Added milk.", reply.text)
        assertNull(reply.notice)
        assertEquals(listOf(mapOf("title" to "milk")), add.inputs.toList())
        assertEquals(listOf("note_add"), tools.approvalsAsked.toList())
        assertEquals(RouteEndpoint.Kind.DEVICE, reply.route?.chosen?.kind)
        assertTrue((reply.route?.budget?.toolResultTokens ?: 0) > 0, "the tool result is part of the sized request")
        assertTrue(transport.urls.isEmpty(), "nothing may use the network: ${transport.urls}")
        assertEquals(2, backend.seen.size)
    }
}
