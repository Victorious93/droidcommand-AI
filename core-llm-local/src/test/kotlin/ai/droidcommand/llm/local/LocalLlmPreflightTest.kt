package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What [LocalLlmProvider] decides before and around a load, and stopping a reply. FAKE backend throughout: these tests
 * prove the Kotlin logic (what is refused, what the backend is asked for, when generation ends), not llama.cpp.
 */
class LocalLlmPreflightTest {
    private class Backend : InferenceBackend {
        override val kind = BackendKind.CPU
        val loadedContexts = mutableListOf<Int>()
        var unloads = 0
        var stopRequests = 0
        var failLoad: String? = null
        var tokens: Sequence<String> = sequenceOf("a", "b")
        var onGenerate: () -> Unit = {}
        var emitted = 0

        override fun load(modelPath: String, contextTokens: Int) {
            failLoad?.let { throw InferenceException(it) }
            loadedContexts += contextTokens
        }

        override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
            onGenerate()
            for (t in tokens) {
                emitted++
                if (!onToken(t)) return
            }
        }

        override fun requestStop() {
            stopRequests++
        }

        override fun unload() {
            unloads++
        }
    }

    private val dir: File = Files.createTempDirectory("preflight").toFile().also { it.deleteOnExit() }
    private val backend = Backend()
    private val request = LlmRequest(null, listOf(Message(Role.USER, "hi")))

    private fun provider(bytes: ByteArray, registeredContext: Int = 2048, memory: DeviceMemory? = null, id: String = "m"): LocalLlmProvider {
        File(dir, "$id.gguf").writeBytes(bytes)
        val repo = ModelRepository(dir, requireGguf = true)
        repo.register(ModelMetadata(id, "My Model", "$id.gguf", ModelRepository.sha256Hex(bytes.inputStream()), registeredContext, bytes.size.toLong()))
        return LocalLlmProvider(id, repo, backend, memory = memory)
    }

    private fun unavailable(r: LlmResponse) = assertIs<LlmError.ModelUnavailable>(assertIs<LlmResponse.Error>(r).error).message

    @Test
    fun `the model is loaded with its trained window when that is smaller than the registered one`() {
        assertIs<LlmResponse.Text>(provider(ggufModelBytes(contextLength = 512), registeredContext = 2048).complete(request))
        assertEquals(listOf(512), backend.loadedContexts)
    }

    @Test
    fun `a larger trained window does not raise the registered one`() {
        assertIs<LlmResponse.Text>(provider(ggufModelBytes(contextLength = 131072), registeredContext = 2048).complete(request))
        assertEquals(listOf(2048), backend.loadedContexts)
    }

    @Test
    fun `a message that fits the registered window but not the trained one is refused before loading`() {
        val long = LlmRequest(null, listOf(Message(Role.USER, "word ".repeat(400))))
        val message = unavailable(provider(ggufModelBytes(contextLength = 256)).complete(long))
        assertTrue(message.contains("context window is 256 tokens"), message)
        assertEquals(emptyList(), backend.loadedContexts)
    }

    @Test
    fun `a file that is not a chat model is refused before it is hashed or loaded`() {
        val message = unavailable(provider(ggufModelBytes { u16("split.count", 4) }).complete(request))
        assertTrue(message.contains("split into 4 files"), message)
        assertEquals(emptyList(), backend.loadedContexts)
    }

    @Test
    fun `the RAM check uses the model's own cache cost`() {
        val mb = 1024L * 1024L
        // 300 MB of weights on a 1 GB device: the floor estimate (300 + 256 + 4 MB) fits, the real cache cost
        // (8192 tokens x 2 MB each for this deliberately huge shape) does not.
        val bytes = ggufModelBytes(contextLength = 8192, payload = ByteArray(8)) {
            u32("llama.block_count", 64)
            u32("llama.embedding_length", 16384)
            u32("llama.attention.head_count", 2)
            u32("llama.attention.head_count_kv", 2)
        }
        File(dir, "big.gguf").writeBytes(bytes)
        val repo = ModelRepository(dir, requireGguf = true)
        repo.register(ModelMetadata("big", "Big", "big.gguf", ModelRepository.sha256Hex(bytes.inputStream()), 8192, 300 * mb))
        val p = LocalLlmProvider("big", repo, backend, memory = { MemorySnapshot(1024 * mb, 900 * mb) })
        assertTrue(unavailable(p.complete(request)).contains("more than this device's 1024 MB"))
        assertEquals(emptyList(), backend.loadedContexts)
    }

    @Test
    fun `a load failure says which model, its architecture, llama cpp's reason, and what to try`() {
        backend.failLoad = "llama.cpp failed to load the model file (llama.cpp: unknown model architecture: 'newthing')"
        val message = unavailable(provider(ggufModelBytes(architecture = "newthing")).complete(request))
        assertTrue(message.contains("Could not load 'My Model'"), message)
        assertTrue(message.contains("architecture 'newthing', GGUF v3"), message)
        assertTrue(message.contains("unknown model architecture"), message)
        assertTrue(message.contains("newer llama.cpp"), message)
        // The failed load is not remembered as loaded: the next call tries again.
        backend.failLoad = null
        assertIs<LlmResponse.Text>(provider(ggufModelBytes(architecture = "newthing")).complete(request))
    }

    @Test
    fun `a file that vanished or changed is refused with what to do`() {
        val p = provider(ggufModelBytes())
        File(dir, "m.gguf").appendBytes(byteArrayOf(1))
        // Size is checked first (cheapest), so a changed size is an integrity rejection.
        assertTrue(unavailable(p.complete(request)).contains("integrity check"))
        File(dir, "m.gguf").delete()
        assertTrue(unavailable(p.complete(request)).contains("no longer there"))
        assertEquals(emptyList(), backend.loadedContexts)
    }

    @Test
    fun `stopping ends generation at the next token and returns the text so far`() {
        backend.tokens = generateSequence { "x" } // would never end on its own
        val stop = AtomicBoolean(false)
        val seen = StringBuilder()
        val r = provider(ggufModelBytes()).stream(request, stop::get) {
            seen.append(it)
            if (seen.length == 3) stop.set(true)
        }
        assertEquals("xxx", assertIs<LlmResponse.Text>(r).content)
        assertEquals("xxx", seen.toString())
        assertEquals(4, backend.emitted, "generation must end at the first token after the stop")
    }

    @Test
    fun `a stop that is already set skips the load entirely`() {
        val r = provider(ggufModelBytes()).stream(request, { true }) { error("no text expected") }
        assertEquals("", assertIs<LlmResponse.Text>(r).content)
        assertEquals(emptyList(), backend.loadedContexts)
    }

    @Test
    fun `requestStop reaches the backend from another thread while a reply is being generated`() {
        val generating = CountDownLatch(1)
        val release = CountDownLatch(1)
        backend.onGenerate = {
            generating.countDown()
            release.await(10, TimeUnit.SECONDS) // stands for a long prompt evaluation
        }
        val p = provider(ggufModelBytes())
        val worker = thread { p.complete(request) }
        assertTrue(generating.await(10, TimeUnit.SECONDS))
        p.requestStop() // must not wait for the lock stream() holds
        assertEquals(1, backend.stopRequests)
        release.countDown()
        worker.join(10_000)
    }

    @Test
    fun `the pool keeps one model loaded and shares it`() {
        val bytes = ggufModelBytes()
        val repo = ModelRepository(dir, requireGguf = true)
        for (id in listOf("a", "b")) {
            File(dir, "$id.gguf").writeBytes(bytes)
            repo.register(ModelMetadata(id, id, "$id.gguf", ModelRepository.sha256Hex(bytes.inputStream()), 2048))
        }
        val backends = mutableListOf<Backend>()
        val pool = LocalProviderPool(repo) { Backend().also { backends += it } }
        val a = pool.provider("a")
        assertSame(a, pool.provider("a"), "two chats on the same model must share one loaded copy")
        a.complete(request)
        pool.provider("a").complete(request)
        assertEquals(1, backends.single().loadedContexts.size)
        pool.provider("b").complete(request) // switching models frees the previous one first
        assertEquals(1, backends[0].unloads)
        assertEquals(1, backends[1].loadedContexts.size)
        pool.close("b")
        assertEquals(1, backends[1].unloads)
        pool.provider("a").complete(request)
        assertEquals(2, backends[0].loadedContexts.size, "a model that was unloaded loads again on its next use")
        pool.closeAll()
        assertEquals(2, backends[0].unloads)
    }
}
