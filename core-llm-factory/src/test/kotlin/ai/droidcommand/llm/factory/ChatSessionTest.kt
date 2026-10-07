package ai.droidcommand.llm.factory

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.google.GeminiLlmProvider
import ai.droidcommand.llm.groq.GroqLlmProvider
import ai.droidcommand.remote.JdkHttpTransport
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [ChatSession] end to end against local mock servers — never a live API, never a real key. */
class ChatSessionTest {
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private val groq = CloudProviderCatalog.byId("groq")!!
    private val google = CloudProviderCatalog.byId("google")!!
    private val key = "gsk-test-key-1234567890"

    private fun start(status: Int = 200, captured: AtomicReference<String>? = null, body: (String) -> String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        s.createContext("/") { ex ->
            val req = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            captured?.set(req)
            val bytes = body(req).toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun session(
        base: String,
        store: InMemoryConversationStore = InMemoryConversationStore(),
        vaultKey: String? = key,
    ): Triple<ChatSession, InMemoryConversationStore, InMemorySecretsVault> {
        val vault = InMemorySecretsVault()
        vaultKey?.let {
            vault.putSecret(groq.secretId, it)
            vault.putSecret(google.secretId, it)
        }
        val factory = { spec: CloudProviderSpec, config: LlmConfig ->
            val c = LlmConfig(config.provider, config.model, base, authToken = config.authToken)
            val p: LlmProvider = if (spec.id == "google") GeminiLlmProvider(c, JdkHttpTransport(), requireHttps = false) else GroqLlmProvider(c, JdkHttpTransport(), requireHttps = false)
            p
        }
        return Triple(ChatSession(vault, store, transport = JdkHttpTransport(), providerFor = factory), store, vault)
    }

    @Test
    fun `streams deltas, returns the reply, and persists both messages`() {
        val base = start { sse("""{"choices":[{"delta":{"content":"Hel"}}]}""", """{"choices":[{"delta":{"content":"lo"}}]}""") }
        val (chat, store, _) = session(base)
        val deltas = mutableListOf<String>()

        val result = chat.send(groq, "", "  hi  ") { deltas += it }

        assertEquals(ChatResult.Reply("Hello"), result)
        assertEquals(listOf("Hel", "lo"), deltas)
        assertEquals(listOf(Role.USER to "hi", Role.ASSISTANT to "Hello"), chat.history().map { it.role to it.content })
        assertEquals(2, store.load("default")!!.messages.size)
    }

    @Test
    fun `a new session over the same store resumes the conversation and sends it as context`() {
        val seen = AtomicReference<String>()
        val base = start(captured = seen) { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val store = InMemoryConversationStore()
        session(base, store).first.send(groq, "m1", "first")

        val resumed = session(base, store).first
        assertEquals(2, resumed.history().size)
        resumed.send(groq, "m1", "second")

        assertTrue(seen.get().contains("first") && seen.get().contains("second"), seen.get())
        assertEquals(4, store.load("default")!!.messages.size)
    }

    @Test
    fun `a missing key fails without any request and leaves history untouched`() {
        val seen = AtomicReference<String>()
        val base = start(captured = seen) { sse("""{"choices":[{"delta":{"content":"x"}}]}""") }
        val (chat, _, _) = session(base, vaultKey = null)

        val result = chat.send(groq, "m", "hi")

        assertTrue(assertIs<ChatResult.Failure>(result).message.contains("Add one in Settings"))
        assertEquals(null, seen.get())
        assertTrue(chat.history().isEmpty())
    }

    @Test
    fun `a provider error that echoes the key is scrubbed and the turn is not recorded`() {
        val base = start(status = 401) { """{"error":{"message":"Invalid API key: $key"}}""" }
        val (chat, store, _) = session(base)

        val failure = assertIs<ChatResult.Failure>(chat.send(groq, "m", "hi"))

        assertFalse(failure.message.contains(key), failure.message)
        assertTrue(chat.history().isEmpty())
        assertEquals(null, store.load("default"))
    }

    @Test
    fun `blank input and empty replies are failures`() {
        val base = start { sse("""{"choices":[{"delta":{"content":""}}]}""") }
        val (chat, _, _) = session(base)

        assertIs<ChatResult.Failure>(chat.send(groq, "m", "   "))
        assertIs<ChatResult.Failure>(chat.send(groq, "m", "hi"))
        assertTrue(chat.history().isEmpty())
    }

    @Test
    fun `works through the Gemini provider too and reset clears storage`() {
        val base = start { """data: {"candidates":[{"content":{"parts":[{"text":"gem"}]}}]}""" + "\n\n" }
        val (chat, store, _) = session(base)

        assertEquals(ChatResult.Reply("gem"), chat.send(google, "gemini-x", "hi"))
        chat.reset()

        assertTrue(chat.history().isEmpty())
        assertEquals(null, store.load("default"))
    }

    @Test
    fun `construction does not touch the store`() {
        val store = object : ai.droidcommand.agent.ConversationStore by InMemoryConversationStore() {
            override fun load(conversationId: String) = error("store must not be read at construction")
        }

        ChatSession(InMemorySecretsVault(), store, transport = JdkHttpTransport())
    }

    @Test
    fun `the catalog covers the four providers with distinct secret ids`() {
        assertEquals(setOf("anthropic", "openai", "google", "groq"), CloudProviderCatalog.all.map { it.id }.toSet())
        assertEquals(4, CloudProviderCatalog.all.map { it.secretId }.toSet().size)
    }
}
