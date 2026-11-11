package ai.droidcommand.llm.factory

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.LocalProviderPool
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.websearch.WebSearchClient
import ai.droidcommand.websearch.WebSearchOutcome
import ai.droidcommand.websearch.WebSearchResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On-device turns in [ChatSession]: stopping, and what an on-device turn must never do (search the web, offer tools,
 * touch the network). FAKE backend (no llama.cpp); the transport fails the test if anything uses it.
 */
class ChatSessionOnDeviceTest {
    private class Backend(private val tokens: Sequence<String> = sequenceOf("local ", "answer")) : InferenceBackend {
        override val kind = BackendKind.CPU
        var loads = 0
        var stopRequests = 0
        var emitted = 0
        val seen = mutableListOf<GenerationRequest>()
        var onGenerate: () -> Unit = {}

        override fun load(modelPath: String, contextTokens: Int) {
            loads++
        }

        override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
            seen += request
            onGenerate()
            for (t in tokens) {
                emitted++
                if (!onToken(t)) return
            }
        }

        override fun requestStop() {
            stopRequests++
        }

        override fun unload() = Unit
    }

    /** Any request through this is a test failure: an on-device turn must stay on the device. */
    private class NoNetwork : HttpTransport {
        val requests = mutableListOf<String>()

        override fun send(request: HttpRequestSpec): HttpResponseSpec {
            requests += request.url
            error("an on-device turn used the network: ${request.url}")
        }
    }

    private class CountingSearch : WebSearchClient {
        val queries = mutableListOf<String>()

        override fun search(query: String, count: Int): WebSearchOutcome {
            queries += query
            return WebSearchOutcome.Success(listOf(WebSearchResult("t", "https://example.com", "s")))
        }
    }

    private class Runner : ToolRunner {
        var calls = 0

        override fun run(
            toolName: String,
            input: Map<String, String>,
            retryPolicy: RetryPolicy,
            isCancelled: () -> Boolean,
            mode: AgentMode?,
            initiator: Initiator?,
            grantId: String?,
        ): ToolResult {
            calls++
            return ToolResult.Success("ran")
        }
    }

    private val dir: File = Files.createTempDirectory("chat-on-device").toFile().also { it.deleteOnExit() }
    private val bytes = "weights".toByteArray()
    private val store = InMemoryConversationStore()
    private val network = NoNetwork()
    private val search = CountingSearch()

    private fun repository() = ModelRepository(dir).also {
        File(dir, "m.gguf").writeBytes(bytes)
        it.register(ModelMetadata("tiny", "Tiny", "m.gguf", ModelRepository.sha256Hex(bytes.inputStream()), 2048))
    }

    // No API key anywhere: an on-device chat must work with an empty vault.
    private fun session(resources: LocalProviderResources) =
        ChatSession(InMemorySecretsVault(), store, transport = network, webSearch = search, local = resources)

    private fun session(backend: Backend) = session(LocalProviderResources(repository()) { backend })

    @Test
    fun `stopping ends the generation, saves nothing, and the next message works`() {
        val backend = Backend(generateSequence("x") { "x" }) // never ends on its own
        val chat = session(backend)
        val stop = AtomicBoolean(false)
        val shown = StringBuilder()
        val stopped = chat.sendLocal("tiny", "tell me a long story", cancelled = stop::get) {
            shown.append(it)
            if (shown.length == 5) stop.set(true)
        }
        assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), stopped)
        assertEquals("xxxxx", shown.toString(), "no text may be delivered after the stop")
        assertEquals(6, backend.emitted, "generation must end at the first token after the stop")
        assertNull(store.load("default"), "a stopped reply must not be written into the saved chat")
        assertTrue(chat.history().isEmpty())

        val again = chat.sendLocal("tiny", "hi", cancelled = { backend.emitted >= 9 }) {}
        assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), again) // still stoppable, and not stuck behind the first
        assertEquals(1, backend.loads, "stopping must not unload the model")
    }

    @Test
    fun `a stop requested before the turn starts sends nothing to the model`() {
        val backend = Backend()
        assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), session(backend).sendLocal("tiny", "hi", cancelled = { true }))
        assertEquals(0, backend.loads)
        assertTrue(backend.seen.isEmpty())
    }

    @Test
    fun `requestStopLocal reaches the backend while it is still reading the prompt`() {
        val backend = Backend()
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onGenerate = {
            reading.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val chat = session(backend)
        val stop = AtomicBoolean(false)
        var result: ChatResult? = null
        val worker = thread { result = chat.sendLocal("tiny", "hi", cancelled = stop::get) {} }
        assertTrue(reading.await(10, TimeUnit.SECONDS))
        stop.set(true)
        chat.requestStopLocal() // must not wait for the session lock the turn holds
        assertEquals(1, backend.stopRequests)
        release.countDown()
        worker.join(10_000)
        assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), result)
        chat.requestStopLocal() // nothing is generating now: a no-op, not a stale stop
        assertEquals(1, backend.stopRequests)
    }

    @Test
    fun `web search is never run for an on-device turn, even when asked for`() {
        val backend = Backend()
        val reply = assertIs<ChatResult.Reply>(session(backend).sendLocal("tiny", "private question", useWebSearch = true))
        assertEquals("local answer", reply.text)
        assertEquals(ChatSession.LOCAL_WEB_SEARCH_NOTICE, reply.notice)
        assertTrue(search.queries.isEmpty(), "the message text must not be sent to a search provider")
        assertEquals(listOf("private question"), backend.seen.single().messages.map { it.content }, "no search block is added to the prompt")
        assertTrue(network.requests.isEmpty())
    }

    @Test
    fun `an on-device turn with a helper bot's tools answers without them instead of failing`() {
        val backend = Backend()
        val chat = session(backend)
        val runner = Runner()
        chat.setToolAccess(runner, listOf(ToolSpec(name = "echo", description = "Repeats text")))
        val reply = assertIs<ChatResult.Reply>(chat.sendLocal("tiny", "hi"))
        assertEquals("local answer", reply.text)
        assertEquals(ChatSession.LOCAL_TOOLS_NOTICE, reply.notice)
        assertEquals(0, runner.calls)
    }

    @Test
    fun `an on-device chat needs no key and makes no request, turn after turn`() {
        val backend = Backend()
        val chat = session(backend)
        repeat(3) { assertIs<ChatResult.Reply>(chat.sendLocal("tiny", "message $it")) }
        // Remembering needs a cloud model; after on-device turns it refuses rather than contacting one.
        assertIs<MemoryResult.NothingToRemember>(chat.rememberConversation()) // no graph wired: nothing to do, no request
        assertTrue(network.requests.isEmpty())
        assertTrue(search.queries.isEmpty())
        assertEquals(6, store.load("default")!!.messages.size)
    }

    @Test
    fun `sessions that share a pool share one loaded copy of the model`() {
        val repo = repository()
        val backends = mutableListOf<Backend>()
        val factory = { Backend().also { backends += it } }
        val resources = LocalProviderResources(repo, pool = LocalProviderPool(repo, null, factory), backendFactory = factory)
        val chat = session(resources)
        val assist = ChatSession(InMemorySecretsVault(), InMemoryConversationStore(), transport = network, local = resources)
        assertIs<ChatResult.Reply>(chat.sendLocal("tiny", "a"))
        assertIs<ChatResult.Reply>(assist.sendLocal("tiny", "b"))
        assist.close() // a one-shot user going away must not unload the chat's model
        assertIs<ChatResult.Reply>(chat.sendLocal("tiny", "c"))
        assertEquals(1, backends.size)
        assertEquals(1, backends.single().loads)
    }
}
