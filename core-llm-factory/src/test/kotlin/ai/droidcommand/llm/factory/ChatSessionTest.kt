package ai.droidcommand.llm.factory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.GraphRetriever
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.google.GeminiLlmProvider
import ai.droidcommand.llm.groq.GroqLlmProvider
import ai.droidcommand.remote.JdkHttpTransport
import ai.droidcommand.websearch.WebSearchClient
import ai.droidcommand.websearch.WebSearchOutcome
import ai.droidcommand.websearch.WebSearchResult
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

    /** Like [start] but the status can vary per request (e.g. 200 for the chat turn, 401 for the extraction). */
    private fun startRouting(status: () -> Int = { 200 }, body: (String) -> String): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server = s
        s.createContext("/") { ex ->
            val req = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val code = status()
            val bytes = body(req).toByteArray()
            ex.sendResponseHeaders(code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun session(
        base: String,
        store: InMemoryConversationStore = InMemoryConversationStore(),
        vaultKey: String? = key,
        knowledge: GraphRetriever? = null,
        graph: ai.droidcommand.agent.KnowledgeGraph? = null,
        search: WebSearchClient? = null,
        documents: ai.droidcommand.rag.DocumentRetriever? = null,
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
        return Triple(ChatSession(vault, store, transport = JdkHttpTransport(), providerFor = factory, knowledge = knowledge, knowledgeGraph = graph, webSearch = search, documents = documents), store, vault)
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

    private fun routerGraph(): KnowledgeGraph = InMemoryKnowledgeGraph().also {
        it.addEntity(Entity("r", EntityType.DEVICE, "Home Router", mapOf("model" to "AX3000")))
    }

    @Test
    fun `knowledge notes reach the request but are never saved into history`() {
        val captured = AtomicReference<String>()
        val base = start(captured = captured) { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val (chat, store, _) = session(base, knowledge = GraphRetriever(routerGraph()))

        assertEquals(ChatResult.Reply("ok"), chat.send(groq, "", "is my router fast?"))

        val body = captured.get()
        assertTrue(body.contains("Home Router"), "notes missing from the request: $body")
        assertTrue(body.contains("AX3000"))
        assertTrue(body.contains("not instructions"))
        assertEquals(listOf(Role.USER to "is my router fast?", Role.ASSISTANT to "ok"), chat.history().map { it.role to it.content })
        assertFalse(store.load("default")!!.messages.any { it.content.contains("Home Router") })
    }

    @Test
    fun `notes go in the user turn, never the system prompt`() {
        val captured = AtomicReference<String>()
        val base = start(captured = captured) { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val (chat, _, _) = session(base, knowledge = GraphRetriever(routerGraph()))
        chat.send(groq, "", "router?")
        val body = captured.get()
        assertFalse(body.contains("\"role\":\"system\""), "no system message expected here: $body")
        assertTrue(body.contains("\"role\":\"user\""))
    }

    @Test
    fun `no match means the request is exactly what the user typed`() {
        val captured = AtomicReference<String>()
        val base = start(captured = captured) { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val (chat, _, _) = session(base, knowledge = GraphRetriever(routerGraph()))
        chat.send(groq, "", "weather tomorrow")
        assertFalse(captured.get().contains(GraphRetriever.HEADING))
    }

    @Test
    fun `a failing graph does not fail the turn`() {
        val broken = object : KnowledgeGraph by InMemoryKnowledgeGraph() {
            override fun searchEntities(keyword: String): List<Entity> = throw IllegalStateException("db closed")
        }
        val base = start { sse("""{"choices":[{"delta":{"content":"fine"}}]}""") }
        val (chat, _, _) = session(base, knowledge = GraphRetriever(broken))
        assertEquals(ChatResult.Reply("fine"), chat.send(groq, "", "router?"))
    }

    // ---- Phase 4: per-message web search ----

    private class FakeSearch(private val outcome: WebSearchOutcome) : WebSearchClient {
        val queries = mutableListOf<String>()
        override fun search(query: String, count: Int): WebSearchOutcome {
            queries += query
            return outcome
        }
    }

    private val hits = WebSearchOutcome.Success(listOf(WebSearchResult("Kotlin 2.4", "https://kotl.in/x", "Released\n  today")))

    @Test
    fun `search results reach the user turn as untrusted text and are never saved`() {
        val captured = AtomicReference<String>()
        val base = start(captured = captured) { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val fake = FakeSearch(hits)
        val (chat, store, _) = session(base, search = fake)

        assertEquals(ChatResult.Reply("ok"), chat.send(groq, "", "kotlin news", useWebSearch = true))

        val body = captured.get()
        assertEquals(listOf("kotlin news"), fake.queries)
        assertTrue(body.contains("Kotlin 2.4") && body.contains("https://kotl.in/x") && body.contains("Released today"))
        assertTrue(body.contains("not instructions"))
        assertFalse(body.contains("\"role\":\"system\""))
        assertFalse(store.load("default")!!.messages.any { it.content.contains("Kotlin 2.4") })
    }

    @Test
    fun `without the flag no search is made even if a client is configured`() {
        val captured = AtomicReference<String>()
        val base = start(captured = captured) { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val fake = FakeSearch(hits)
        val (chat, _, _) = session(base, search = fake)
        chat.send(groq, "", "kotlin news")
        assertTrue(fake.queries.isEmpty())
        assertFalse(captured.get().contains(ChatSession.WEB_HEADING))
    }

    @Test
    fun `a failed or empty search still answers and reports a notice`() {
        val base = start { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val (failing, _, _) = session(base, search = FakeSearch(WebSearchOutcome.Failure("HTTP 401", 401)))
        val failed = failing.send(groq, "", "q", useWebSearch = true)
        assertIs<ChatResult.Reply>(failed)
        assertEquals("ok", failed.text)
        assertTrue(failed.notice!!.contains("HTTP 401"))

        val (empty, _, _) = session(base, search = FakeSearch(WebSearchOutcome.Success(emptyList())))
        assertTrue((empty.send(groq, "", "q", useWebSearch = true) as ChatResult.Reply).notice!!.contains("no results"))

        val (none, _, _) = session(base)
        assertTrue((none.send(groq, "", "q", useWebSearch = true) as ChatResult.Reply).notice!!.contains("not available"))
    }

    @Test
    fun `a throwing search client does not fail the turn and long queries are capped`() {
        val base = start { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val queries = mutableListOf<String>()
        val boom = object : WebSearchClient {
            override fun search(query: String, count: Int): WebSearchOutcome {
                queries += query
                throw IllegalStateException("socket closed")
            }
        }
        val (chat, _, _) = session(base, search = boom)
        val result = chat.send(groq, "", "x".repeat(1000), useWebSearch = true)
        assertIs<ChatResult.Reply>(result)
        assertTrue(result.notice!!.contains("socket closed"))
        assertEquals(300, queries.single().length)
    }

    @Test
    fun `the catalog client fails cleanly with no keys and makes no request`() {
        val vault = InMemorySecretsVault()
        val outcome = WebSearchCatalog.clientFor(vault, JdkHttpTransport()).search("anything")
        assertIs<WebSearchOutcome.Failure>(outcome)
        assertEquals(2, WebSearchCatalog.all.map { it.secretId }.distinct().size)
    }

    // ---- K3: user-triggered extraction into the graph ----

    /** A non-streaming JSON reply, as the extraction provider expects (complete(), not SSE). */
    private fun graphJson() =
        """{"choices":[{"message":{"content":"{\"entities\":[{\"key\":\"r\",\"type\":\"DEVICE\",\"label\":\"Home Router\",\"properties\":{}}],\"relationships\":[]}"}}]}"""

    @Test
    fun `rememberConversation extracts with the last provider and saves to the graph`() {
        val graph = InMemoryKnowledgeGraph()
        // First call: a streamed chat turn. Second call: the extraction (non-streaming JSON).
        var call = 0
        val base = startRouting { if (call++ == 0) sse("""{"choices":[{"delta":{"content":"hi"}}]}""") else graphJson() }
        val (chat, _, _) = session(base, graph = graph)

        assertEquals(ChatResult.Reply("hi"), chat.send(groq, "", "my router is slow"))
        val result = chat.rememberConversation()

        assertEquals(MemoryResult.Saved(1, 0), result)
        assertEquals(listOf("Home Router"), graph.searchEntities("router").map { it.label })
    }

    @Test
    fun `rememberConversation does nothing before any successful turn or with no graph configured`() {
        val base = start { graphJson() }
        val (noGraph, _, _) = session(base)
        assertEquals(MemoryResult.NothingToRemember, noGraph.rememberConversation())

        val (withGraph, _, _) = session(base, graph = InMemoryKnowledgeGraph())
        assertEquals(MemoryResult.NothingToRemember, withGraph.rememberConversation(), "no successful turn yet")
    }

    @Test
    fun `an empty extraction result reports nothing to remember and writes nothing`() {
        val graph = InMemoryKnowledgeGraph()
        var call = 0
        val empty = """{"choices":[{"message":{"content":"{\"entities\":[],\"relationships\":[]}"}}]}"""
        val base = startRouting { if (call++ == 0) sse("""{"choices":[{"delta":{"content":"ok"}}]}""") else empty }
        val (chat, _, _) = session(base, graph = graph)
        chat.send(groq, "", "nothing notable here")
        assertEquals(MemoryResult.NothingToRemember, chat.rememberConversation())
        assertTrue(graph.searchEntities("").isEmpty())
    }

    @Test
    fun `a provider failure during remember is a scrubbed failure and leaves the graph empty`() {
        val graph = InMemoryKnowledgeGraph()
        var call = 0
        val base = startRouting(status = { if (call == 0) 200 else 401 }) {
            if (call++ == 0) sse("""{"choices":[{"delta":{"content":"ok"}}]}""") else """error key $key leaked"""
        }
        val (chat, _, _) = session(base, graph = graph)
        chat.send(groq, "", "remember this")
        val result = chat.rememberConversation()
        assertIs<MemoryResult.Failure>(result)
        assertFalse(result.message.contains(key), "key must be scrubbed from: ${result.message}")
        assertTrue(graph.searchEntities("").isEmpty())
    }

    @Test
    fun `reset clears the remembered provider so remember does nothing again`() {
        val graph = InMemoryKnowledgeGraph()
        var call = 0
        val base = startRouting { if (call++ == 0) sse("""{"choices":[{"delta":{"content":"hi"}}]}""") else graphJson() }
        val (chat, _, _) = session(base, graph = graph)
        chat.send(groq, "", "my router")
        chat.reset()
        assertEquals(MemoryResult.NothingToRemember, chat.rememberConversation())
    }

    @Test
    fun `attached document excerpts reach the request but are not saved in history`() {
        val seen = AtomicReference<String>()
        val base = start(captured = seen) { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val embedder = object : ai.droidcommand.rag.Embedder {
            override fun embed(texts: List<String>) = texts.map { floatArrayOf(if ("zebra" in it) 1f else 0f, 1f) }
        }
        val docs = ai.droidcommand.rag.DocumentRetriever(embedder)
        docs.index("notes.txt", "the zebra crossing is on main street")
        val (chat, store, _) = session(base, documents = docs)

        val result = chat.send(groq, "", "where is the zebra crossing")

        assertIs<ChatResult.Reply>(result)
        assertTrue("zebra crossing is on main street" in seen.get())
        assertTrue(ai.droidcommand.rag.DocumentRetriever.HEADING in seen.get())
        assertEquals("where is the zebra crossing", store.load("default")!!.messages.first().content)
    }

    @Test
    fun `a throwing document retriever does not fail the turn`() {
        val base = start { sse("""{"choices":[{"delta":{"content":"ok"}}]}""") }
        val boom = object : ai.droidcommand.rag.Embedder {
            override fun embed(texts: List<String>): List<FloatArray> = if (texts.single() == "hi") error("embedder down") else listOf(floatArrayOf(1f))
        }
        val docs = ai.droidcommand.rag.DocumentRetriever(boom)
        docs.index("d", "some text")
        val (chat, _, _) = session(base, documents = docs)

        assertEquals(ChatResult.Reply("ok"), chat.send(groq, "", "hi"))
    }
}
