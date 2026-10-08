package ai.droidcommand.llm.factory

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.config.ConfigKeys
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.MapConfigSource
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.local.LocalLlmProvider
import ai.droidcommand.llm.local.ModelMetadata
import ai.droidcommand.llm.local.ModelRepository
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

private class CountingTransport(private val status: Int = 500) : HttpTransport {
    var calls = 0

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        calls++
        return HttpResponseSpec(status, emptyMap(), "{}")
    }
}

private class EchoBackend : InferenceBackend {
    override val kind = BackendKind.CPU
    var loads = 0

    override fun load(modelPath: String, contextTokens: Int) {
        loads++
    }

    override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
        onToken("local-reply")
    }

    override fun unload() = Unit
}

class LocalProviderFactoryTest {
    private val dir: File = Files.createTempDirectory("factory-models").toFile().also { it.deleteOnExit() }
    private val bytes = "model-bytes".toByteArray()
    private val sha = ModelRepository.sha256Hex(bytes.inputStream())

    private fun repository(sha256: String = sha, writeFile: Boolean = true) = ModelRepository(dir).also {
        if (writeFile) File(dir, "m.gguf").writeBytes(bytes)
        it.register(ModelMetadata("tiny", "Tiny", "m.gguf", sha256, 2048))
    }

    private fun localConfigured(model: String = "tiny", type: ProviderType = ProviderType.LOCAL) = ConfiguredLlmProvider(
        LlmConfig(provider = "local", model = model),
        AiProviderInfo("device", "device", type, 2048),
    )

    private val request = LlmRequest(null, listOf(Message(Role.USER, "hi")))

    @Test
    fun `local provider builds a LocalLlmProvider for a registered model`() {
        val registered = LlmProviderFactory.build(
            localConfigured(),
            CountingTransport(),
            LocalProviderResources(repository()) { EchoBackend() },
        )
        assertIs<LocalLlmProvider>(registered.provider)
        assertEquals(LlmResponse.Text("local-reply"), registered.provider.complete(request))
    }

    @Test
    fun `local provider without resources fails at build time`() {
        assertFailsWith<LocalProviderNotConfiguredException> { LlmProviderFactory.build(localConfigured(), CountingTransport()) }
    }

    @Test
    fun `local provider naming an unregistered model fails at build time`() {
        val resources = LocalProviderResources(repository()) { EchoBackend() }
        assertFailsWith<LocalProviderNotConfiguredException> {
            LlmProviderFactory.build(localConfigured(model = "nope"), CountingTransport(), resources)
        }
    }

    @Test
    fun `every local entry gets its own backend instance`() {
        val made = mutableListOf<EchoBackend>()
        val resources = LocalProviderResources(repository()) { EchoBackend().also(made::add) }
        LlmProviderFactory.build(localConfigured(), CountingTransport(), resources)
        LlmProviderFactory.build(localConfigured(), CountingTransport(), resources)
        assertEquals(2, made.size)
        assertNotSame(made[0], made[1])
        assertTrue(made.all { it.loads == 0 }, "backends must load lazily")
    }

    private fun mixedSource() = MapConfigSource(
        mapOf(
            ConfigKeys.LLM_PROVIDER_IDS to "cloud-one,device",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_PROVIDER" to "anthropic",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_MODEL" to "claude-x",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_TYPE" to "CLOUD",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_MAX_CONTEXT_TOKENS" to "200000",
            // Without a key the cloud provider fails fast before sending, which would hide a fallback.
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_API_KEY" to "test-key",
            "DROIDCOMMAND_LLM_PROVIDER_DEVICE_PROVIDER" to "local",
            "DROIDCOMMAND_LLM_PROVIDER_DEVICE_MODEL" to "tiny",
            "DROIDCOMMAND_LLM_PROVIDER_DEVICE_TYPE" to "LOCAL",
            "DROIDCOMMAND_LLM_PROVIDER_DEVICE_MAX_CONTEXT_TOKENS" to "2048",
        ),
    )

    @Test
    fun `router tries the local provider first and never touches the cloud when it works`() {
        val transport = CountingTransport()
        val router = LlmProviderFactory.createModelRouter(mixedSource(), transport, LocalProviderResources(repository()) { EchoBackend() })
        assertEquals(LlmResponse.Text("local-reply"), router.complete(request))
        assertEquals(0, transport.calls)
    }

    @Test
    fun `a tampered local model falls back to the cloud provider instead of loading`() {
        val transport = CountingTransport()
        val backend = EchoBackend()
        val resources = LocalProviderResources(repository(sha256 = "0".repeat(64))) { backend }
        val router = LlmProviderFactory.createModelRouter(mixedSource(), transport, resources)
        router.complete(request)
        assertEquals(0, backend.loads, "a model that fails its SHA-256 check must never reach the backend")
        assertTrue(transport.calls > 0, "router should have fallen back to the cloud provider")
    }

    @Test
    fun `unknown provider message now lists local`() {
        val e = assertFailsWith<UnknownLlmProviderException> {
            LlmProviderFactory.build(
                ConfiguredLlmProvider(LlmConfig("bogus", "m"), AiProviderInfo("x", "x", ProviderType.CLOUD, 1)),
                CountingTransport(),
            )
        }
        assertTrue(e.message!!.contains("local"))
    }
}
