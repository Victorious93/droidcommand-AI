package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.Task
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.DefaultAiProviderSelector
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ProviderPreferences
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.RegisteredProvider
import ai.droidcommand.llm.completeStreaming
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeBackend(override val kind: BackendKind = BackendKind.CPU, val tokens: List<String> = listOf("Hel", "lo")) :
    InferenceBackend {
    var loads = 0
    var unloads = 0
    var lastRequest: GenerationRequest? = null
    var failGenerate = false

    override fun load(modelPath: String, contextTokens: Int) {
        loads++
    }
    override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
        lastRequest = request
        if (failGenerate) throw InferenceException("boom")
        for (t in tokens) if (!onToken(t)) return
    }
    override fun unload() {
        unloads++
    }
}

class LocalLlmTest {
    private val dir: File = Files.createTempDirectory("models").toFile().also { it.deleteOnExit() }
    private val content = "tiny-model-bytes".toByteArray()
    private val goodSha = ModelRepository.sha256Hex(content.inputStream())

    private fun repo(sha: String = goodSha, write: Boolean = true) = ModelRepository(dir).also {
        if (write) File(dir, "m.gguf").writeBytes(content)
        it.register(ModelMetadata("m", "M", "m.gguf", sha, 2048))
    }

    private val req = LlmRequest("sys", listOf(Message(Role.USER, "hi")))

    @Test fun `sha256 matches known vector`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ModelRepository.sha256Hex("abc".byteInputStream()))
    }

    @Test fun `verify distinguishes verified missing and mismatch`() {
        assertIs<ModelCheck.Verified>(repo().verify("m"))
        File(dir, "m.gguf").delete()
        assertIs<ModelCheck.Missing>(ModelRepository(dir).also { it.register(ModelMetadata("m", "M", "m.gguf", goodSha, 1)) }.verify("m"))
        File(dir, "m.gguf").writeBytes("tampered".toByteArray())
        assertIs<ModelCheck.Mismatch>(repo(write = false).verify("m"))
    }

    @Test fun `metadata rejects bad digest and path traversal`() {
        assertFailsWith<IllegalArgumentException> { ModelMetadata("a", "A", "a.gguf", "XYZ", 1) }
        assertFailsWith<IllegalArgumentException> { ModelMetadata("a", "A", "../a.gguf", goodSha, 1) }
    }

    @Test fun `streams deltas and loads once`() {
        val backend = FakeBackend()
        val p = LocalLlmProvider("m", repo(), backend)
        val seen = mutableListOf<String>()
        val r = p.completeStreaming(req) { seen += it }
        assertEquals(LlmResponse.Text("Hello"), r)
        assertEquals(listOf("Hel", "lo"), seen)
        p.complete(req)
        assertEquals(1, backend.loads)
        assertEquals("sys", backend.lastRequest?.systemPrompt)
        p.close()
        assertEquals(1, backend.unloads)
    }

    @Test fun `tampered model is refused and never loaded`() {
        val backend = FakeBackend()
        val r = LocalLlmProvider("m", repo(sha = "0".repeat(64)), backend).complete(req)
        assertIs<LlmError.ModelUnavailable>((r as LlmResponse.Error).error)
        assertEquals(0, backend.loads)
    }

    @Test fun `missing model and unknown model are model-unavailable`() {
        val missing = LocalLlmProvider("m", repo(write = false).also { File(dir, "m.gguf").delete() }, FakeBackend()).complete(req)
        assertIs<LlmError.ModelUnavailable>((missing as LlmResponse.Error).error)
        val unknown = LocalLlmProvider("nope", repo(), FakeBackend()).complete(req)
        assertIs<LlmError.ModelUnavailable>((unknown as LlmResponse.Error).error)
    }

    @Test fun `tools and structured output are rejected not ignored`() {
        val p = LocalLlmProvider("m", repo(), FakeBackend())
        val withFormat = p.complete(req.copy(responseFormat = ai.droidcommand.llm.ResponseFormat.Json))
        assertIs<LlmError.InvalidResponse>((withFormat as LlmResponse.Error).error)
    }

    @Test fun `backend failure maps to error`() {
        val b = FakeBackend().also { it.failGenerate = true }
        val r = LocalLlmProvider("m", repo(), b).complete(req)
        assertEquals("boom", ((r as LlmResponse.Error).error).message)
    }

    @Test fun `backend selection prefers vulkan then opencl then cpu and honors supported override`() {
        assertEquals(BackendKind.CPU, BackendSelector.select(DeviceCapabilities()))
        assertEquals(BackendKind.OPENCL, BackendSelector.select(DeviceCapabilities(openClAvailable = true)))
        val both = DeviceCapabilities(true, true)
        assertEquals(BackendKind.VULKAN, BackendSelector.select(both))
        assertEquals(BackendKind.OPENCL, BackendSelector.select(both, BackendKind.OPENCL))
        assertEquals(BackendKind.CPU, BackendSelector.select(DeviceCapabilities(), BackendKind.VULKAN)) // unsupported override
    }

    @Test fun `benchmark computes throughput from injected clock`() {
        val ticks = ArrayDeque(listOf(0L, 500_000_000L, 2_500_000_000L))
        val r = ModelBenchmark { ticks.removeFirst() }.run(FakeBackend(tokens = List(10) { "t" }), "p", 512)
        assertEquals(500, r.loadMillis)
        assertEquals(10, r.tokensGenerated)
        assertEquals(5.0, r.tokensPerSecond, 1e-9)
    }

    @Test fun `selector filters on measured speed and excludes unmeasured`() {
        val fast = AiProviderInfo("fast", "f", ProviderType.LOCAL, 4096, measuredTokensPerSecond = 20.0)
        val slow = AiProviderInfo("slow", "s", ProviderType.LOCAL, 4096, measuredTokensPerSecond = 2.0)
        val unknown = AiProviderInfo("unk", "u", ProviderType.LOCAL, 4096)
        val dummy = LocalLlmProvider("m", repo(), FakeBackend())
        val sel = DefaultAiProviderSelector(listOf(slow, unknown, fast).map { RegisteredProvider(it, dummy) })
        val task = Task("t", "do it")
        assertTrue(sel.selectProvider(task, ProviderPreferences(minTokensPerSecond = 10.0)) === dummy)
        val onlySlow = DefaultAiProviderSelector(listOf(slow, unknown).map { RegisteredProvider(it, dummy) })
        assertNull(onlySlow.selectProvider(task, ProviderPreferences(minTokensPerSecond = 10.0)))
    }
}
