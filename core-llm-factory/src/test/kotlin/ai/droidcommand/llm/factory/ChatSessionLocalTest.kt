package ai.droidcommand.llm.factory

import ai.droidcommand.agent.GraphRetriever
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.remote.JdkHttpTransport
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Local-model turns in [ChatSession], with a fake backend (no llama.cpp) and a fake cloud provider (no network). */
class ChatSessionLocalTest {
    private class FakeBackend : InferenceBackend {
        override val kind = BackendKind.CPU
        var loads = 0
        var unloads = 0
        val seen = mutableListOf<GenerationRequest>()

        override fun load(modelPath: String, contextTokens: Int) {
            loads++
        }

        override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
            seen += request
            onToken("local ")
            onToken("answer")
        }

        override fun unload() {
            unloads++
        }
    }

    private class FakeCloud(private val reply: String = "cloud answer") : LlmProvider {
        override val config = LlmConfig("groq", "m")
        val requests = mutableListOf<LlmRequest>()

        override fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return LlmResponse.Text(reply)
        }
    }

    private val groq = CloudProviderCatalog.byId("groq")!!
    private val dir: File = Files.createTempDirectory("chat-local").toFile().also { it.deleteOnExit() }
    private val bytes = "weights".toByteArray()
    private val backend = FakeBackend()
    private val cloud = FakeCloud()
    private val store = InMemoryConversationStore()

    private fun repository(sha: String = ModelRepository.sha256Hex(bytes.inputStream())) = ModelRepository(dir).also {
        File(dir, "m.gguf").writeBytes(bytes)
        it.register(ModelMetadata("tiny", "Tiny", "m.gguf", sha, 2048))
    }

    private fun session(
        repo: ModelRepository? = repository(),
        knowledge: GraphRetriever? = null,
    ): ChatSession {
        val vault = InMemorySecretsVault().also { it.putSecret(groq.secretId, "gsk-test-key-1234567890") }
        return ChatSession(
            vault,
            store,
            transport = JdkHttpTransport(),
            providerFor = { _, _ -> cloud },
            knowledge = knowledge,
            knowledgeGraph = InMemoryKnowledgeGraph(),
            local = repo?.let { LocalProviderResources(it) { backend } },
        )
    }

    @Test
    fun `a local turn streams, persists, needs no key, and keeps the model loaded between turns`() {
        val chat = session()
        val deltas = mutableListOf<String>()
        val first = chat.sendLocal("tiny", " hi ") { deltas += it }
        assertEquals(ChatResult.Reply("local answer"), first)
        assertEquals(listOf("local ", "answer"), deltas)
        assertIs<ChatResult.Reply>(chat.sendLocal("tiny", "again"))
        assertEquals(1, backend.loads, "the model must stay loaded between turns")
        assertEquals(4, store.load("default")!!.messages.size)
    }

    @Test
    fun `cloud and local turns share one history in both directions`() {
        val chat = session()
        assertIs<ChatResult.Reply>(chat.send(groq, "", "one"))
        assertIs<ChatResult.Reply>(chat.sendLocal("tiny", "two"))
        assertEquals(listOf("one", "cloud answer", "two"), backend.seen.single().messages.map { it.content })
        assertIs<ChatResult.Reply>(chat.send(groq, "", "three"))
        val sentToCloud = cloud.requests.last().messages.map { it.content }
        assertEquals(listOf("one", "cloud answer", "two", "local answer", "three"), sentToCloud)
    }

    @Test
    fun `without local resources or with an unknown model the turn fails and nothing is saved`() {
        val none = session(repo = null).sendLocal("tiny", "hi")
        assertTrue(assertIs<ChatResult.Failure>(none).message.contains("not available"))
        val unknown = session().sendLocal("nope", "hi")
        assertTrue(assertIs<ChatResult.Failure>(unknown).message.contains("Unknown on-device model"))
        assertNull(store.load("default"))
    }

    @Test
    fun `a tampered model file is refused before it reaches the backend`() {
        val chat = session(repo = repository(sha = "0".repeat(64)))
        val r = chat.sendLocal("tiny", "hi")
        assertTrue(assertIs<ChatResult.Failure>(r).message.contains("SHA-256"), r.toString())
        assertEquals(0, backend.loads)
        assertNull(store.load("default"))
    }

    @Test
    fun `blank messages are rejected for local turns too`() {
        assertEquals(ChatResult.Failure("Message is empty."), session().sendLocal("tiny", "  "))
    }

    @Test
    fun `remembering after a local turn refuses instead of silently using a cloud provider`() {
        val chat = session()
        chat.send(groq, "", "one")
        chat.sendLocal("tiny", "two")
        val r = chat.rememberConversation()
        assertTrue(assertIs<MemoryResult.Failure>(r).message.contains("on-device"))
        assertFalse(cloud.requests.any { it.messages.any { m -> m.role == Role.USER && m.content.contains("Extract") } })
    }

    @Test
    fun `close unloads the model and the next turn reloads it`() {
        val chat = session()
        chat.sendLocal("tiny", "a")
        chat.close()
        assertEquals(1, backend.unloads)
        chat.sendLocal("tiny", "b")
        assertEquals(2, backend.loads)
    }

    @Test
    fun `local replies containing the cloud key shape are not special-cased but errors carry no key`() {
        // The local path has no key to scrub; this guards that a null key does not break scrubbing.
        val failing = object : InferenceBackend {
            override val kind = BackendKind.CPU
            override fun load(modelPath: String, contextTokens: Int) = Unit
            override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) = error("boom")
            override fun unload() = Unit
        }
        val vault = InMemorySecretsVault()
        val chat = ChatSession(vault, store, transport = JdkHttpTransport(), local = LocalProviderResources(repository()) { failing })
        assertIs<ChatResult.Failure>(chat.sendLocal("tiny", "x"))
    }

    @Test
    fun `message class is used by history`() {
        val chat = session()
        chat.sendLocal("tiny", "q")
        assertEquals(listOf(Message(Role.USER, "q"), Message(Role.ASSISTANT, "local answer")), chat.history())
    }
}
