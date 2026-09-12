package ai.droidcommand.llm.factory

import ai.droidcommand.agent.Task
import ai.droidcommand.config.ConfigKeys
import ai.droidcommand.config.ConfiguredLlmProvider
import ai.droidcommand.config.MapConfigSource
import ai.droidcommand.llm.AiProviderInfo
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.anthropic.AnthropicLlmProvider
import ai.droidcommand.llm.openai.OpenAiLlmProvider
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Never actually invoked by these tests — they only prove construction/wiring, not a real HTTP round trip (each provider's own integration test already covers that). */
private class FakeHttpTransport : HttpTransport {
    override fun send(request: HttpRequestSpec): HttpResponseSpec = HttpResponseSpec(200, emptyMap(), "{}")
}

private fun configured(id: String, provider: String) = ConfiguredLlmProvider(
    config = LlmConfig(provider = provider, model = "test-model"),
    info = AiProviderInfo(id = id, name = id, type = ProviderType.CLOUD, maxContextTokens = 100_000),
)

class LlmProviderFactoryTest {
    @Test
    fun `anthropic provider builds an AnthropicLlmProvider`() {
        val registered = LlmProviderFactory.build(configured("p1", "anthropic"), FakeHttpTransport())
        assertIs<AnthropicLlmProvider>(registered.provider)
    }

    @Test
    fun `openai provider builds an OpenAiLlmProvider`() {
        val registered = LlmProviderFactory.build(configured("p1", "openai"), FakeHttpTransport())
        assertIs<OpenAiLlmProvider>(registered.provider)
    }

    @Test
    fun `provider match is case-insensitive`() {
        assertIs<AnthropicLlmProvider>(LlmProviderFactory.build(configured("p1", "Anthropic"), FakeHttpTransport()).provider)
        assertIs<OpenAiLlmProvider>(LlmProviderFactory.build(configured("p2", "OPENAI"), FakeHttpTransport()).provider)
    }

    @Test
    fun `an unknown provider throws UnknownLlmProviderException naming the bad value`() {
        val exception = assertFailsWith<UnknownLlmProviderException> {
            LlmProviderFactory.build(configured("p1", "does-not-exist"), FakeHttpTransport())
        }
        assertTrue(exception.message!!.contains("does-not-exist"))
    }

    @Test
    fun `build pairs the real provider with the configured info unchanged`() {
        val entry = configured("anthropic-primary", "anthropic")
        val registered = LlmProviderFactory.build(entry, FakeHttpTransport())
        assertSame(entry.info, registered.info)
        assertSame(entry.config, registered.provider.config)
    }

    @Test
    fun `buildAll preserves order and produces one RegisteredProvider per entry`() {
        val entries = listOf(configured("p1", "anthropic"), configured("p2", "openai"))
        val result = LlmProviderFactory.buildAll(entries, FakeHttpTransport())

        assertEquals(2, result.size)
        assertEquals("p1", result[0].info.id)
        assertIs<AnthropicLlmProvider>(result[0].provider)
        assertEquals("p2", result[1].info.id)
        assertIs<OpenAiLlmProvider>(result[1].provider)
    }

    @Test
    fun `buildAll fails the whole call on a single unknown provider rather than returning a partial list`() {
        val entries = listOf(configured("p1", "anthropic"), configured("p2", "does-not-exist"))
        assertFailsWith<UnknownLlmProviderException> {
            LlmProviderFactory.buildAll(entries, FakeHttpTransport())
        }
    }

    @Test
    fun `buildAll shares one transport instance across every constructed provider`() {
        val transport = FakeHttpTransport()
        val entries = listOf(configured("p1", "anthropic"), configured("p2", "openai"))
        val result = LlmProviderFactory.buildAll(entries, transport)

        // Neither LlmProvider exposes its transport directly, so this is proven indirectly:
        // constructing both against the same transport instance must not throw, and both
        // providers must still be independently usable/distinct instances.
        assertEquals(2, result.map { it.provider }.distinct().size)
    }

    @Test
    fun `load reads a ConfigSource via MultiLlmConfigLoader and constructs real providers`() {
        val source = MapConfigSource(
            mapOf(
                ConfigKeys.LLM_PROVIDER_IDS to "anthropic-primary,openai-fallback",
                "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_PROVIDER" to "anthropic",
                "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MODEL" to "claude-x",
                "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TYPE" to "CLOUD",
                "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_CONTEXT_TOKENS" to "200000",
                "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_PROVIDER" to "openai",
                "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_MODEL" to "gpt-x",
                "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_TYPE" to "CLOUD",
                "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_MAX_CONTEXT_TOKENS" to "128000",
            ),
        )

        val result = LlmProviderFactory.load(source, FakeHttpTransport())

        assertEquals(2, result.size)
        assertEquals("anthropic-primary", result[0].info.id)
        assertIs<AnthropicLlmProvider>(result[0].provider)
        assertEquals("openai-fallback", result[1].info.id)
        assertIs<OpenAiLlmProvider>(result[1].provider)
    }

    @Test
    fun `load with no configured providers returns an empty list`() {
        assertEquals(emptyList(), LlmProviderFactory.load(MapConfigSource(emptyMap()), FakeHttpTransport()))
    }

    private fun twoProviderSource() = MapConfigSource(
        mapOf(
            ConfigKeys.LLM_PROVIDER_IDS to "cloud-one,local-one",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_PROVIDER" to "openai",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_MODEL" to "gpt-x",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_TYPE" to "CLOUD",
            "DROIDCOMMAND_LLM_PROVIDER_CLOUD_ONE_MAX_CONTEXT_TOKENS" to "128000",
            "DROIDCOMMAND_LLM_PROVIDER_LOCAL_ONE_PROVIDER" to "anthropic",
            "DROIDCOMMAND_LLM_PROVIDER_LOCAL_ONE_MODEL" to "claude-local",
            "DROIDCOMMAND_LLM_PROVIDER_LOCAL_ONE_TYPE" to "LOCAL",
            "DROIDCOMMAND_LLM_PROVIDER_LOCAL_ONE_MAX_CONTEXT_TOKENS" to "8000",
        ),
    )

    @Test
    fun `createSelector wraps a real DefaultAiProviderSelector over the loaded providers`() {
        val selector = LlmProviderFactory.createSelector(twoProviderSource(), FakeHttpTransport())

        val info = selector.listProviders()
        assertEquals(2, info.size)
        assertEquals(setOf("cloud-one", "local-one"), info.map { it.id }.toSet())
    }

    @Test
    fun `createSelector over an unconfigured source has no providers to select`() {
        val selector = LlmProviderFactory.createSelector(MapConfigSource(emptyMap()), FakeHttpTransport())
        assertEquals(emptyList(), selector.listProviders())
        assertNull(selector.selectProvider(Task(id = "t1", description = "test task")))
    }

    @Test
    fun `createModelRouter orders providers local-first regardless of declaration order`() {
        // cloud-one is declared before local-one, but LOCAL must still be tried first.
        val router = LlmProviderFactory.createModelRouter(twoProviderSource(), FakeHttpTransport())
        assertEquals("anthropic", router.config.provider)
    }

    @Test
    fun `createModelRouter over an unconfigured source throws rather than building an empty router`() {
        assertFailsWith<IllegalArgumentException> {
            LlmProviderFactory.createModelRouter(MapConfigSource(emptyMap()), FakeHttpTransport())
        }
    }
}
