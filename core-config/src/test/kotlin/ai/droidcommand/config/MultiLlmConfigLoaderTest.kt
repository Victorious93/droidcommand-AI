package ai.droidcommand.config

import ai.droidcommand.llm.Cost
import ai.droidcommand.llm.ProviderCapability
import ai.droidcommand.llm.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiLlmConfigLoaderTest {
    @Test
    fun `no LLM_PROVIDER_IDS yields an empty list`() {
        assertEquals(emptyList(), MultiLlmConfigLoader.load(MapConfigSource(emptyMap())))
    }

    @Test
    fun `blank LLM_PROVIDER_IDS yields an empty list`() {
        val source = MapConfigSource(mapOf(ConfigKeys.LLM_PROVIDER_IDS to "  "))
        assertEquals(emptyList(), MultiLlmConfigLoader.load(source))
    }

    private fun fullSource(id: String = "anthropic-primary") = mapOf(
        ConfigKeys.LLM_PROVIDER_IDS to id,
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_PROVIDER" to "anthropic",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MODEL" to "claude-x",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TYPE" to "CLOUD",
        "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_CONTEXT_TOKENS" to "200000",
    )

    @Test
    fun `one fully-configured provider loads correct LlmConfig and AiProviderInfo fields`() {
        val values = fullSource() + mapOf(
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_NAME" to "Claude Primary",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_ENDPOINT" to "https://example.invalid",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TEMPERATURE" to "0.4",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_OUTPUT_TOKENS" to "2048",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_AVAILABLE" to "false",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_CAPABILITIES" to "tool_calling, vision",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_COST_INPUT_PER_MILLION" to "3.0",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_COST_OUTPUT_PER_MILLION" to "15.0",
        )
        val result = MultiLlmConfigLoader.load(MapConfigSource(values))

        assertEquals(1, result.size)
        val (config, info) = result.single()

        assertEquals("anthropic", config.provider)
        assertEquals("claude-x", config.model)
        assertEquals("https://example.invalid", config.endpoint)
        assertEquals(0.4, config.temperature)
        assertEquals(2048, config.maxOutputTokens)

        assertEquals("anthropic-primary", info.id)
        assertEquals("Claude Primary", info.name)
        assertEquals(ProviderType.CLOUD, info.type)
        assertEquals(200000, info.maxContextTokens)
        assertEquals(false, info.available)
        assertEquals(setOf(ProviderCapability.TOOL_CALLING, ProviderCapability.VISION), info.capabilities)
        assertEquals(Cost(3.0, 15.0), info.cost)
    }

    @Test
    fun `NAME defaults to the id when absent`() {
        val result = MultiLlmConfigLoader.load(MapConfigSource(fullSource()))
        assertEquals("anthropic-primary", result.single().info.name)
    }

    @Test
    fun `AVAILABLE defaults to true when absent`() {
        val result = MultiLlmConfigLoader.load(MapConfigSource(fullSource()))
        assertEquals(true, result.single().info.available)
    }

    @Test
    fun `CAPABILITIES defaults to empty when absent`() {
        val result = MultiLlmConfigLoader.load(MapConfigSource(fullSource()))
        assertEquals(emptySet(), result.single().info.capabilities)
    }

    @Test
    fun `COST is null when neither key is present`() {
        val result = MultiLlmConfigLoader.load(MapConfigSource(fullSource()))
        assertNull(result.single().info.cost)
    }

    @Test
    fun `two providers load independently, order preserved`() {
        val values = mapOf(
            ConfigKeys.LLM_PROVIDER_IDS to "anthropic-primary,openai-fallback",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_PROVIDER" to "anthropic",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MODEL" to "claude-x",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TYPE" to "CLOUD",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_CONTEXT_TOKENS" to "200000",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_PROVIDER" to "openai",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_MODEL" to "gpt-x",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_TYPE" to "cloud",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_MAX_CONTEXT_TOKENS" to "128000",
        )
        val result = MultiLlmConfigLoader.load(MapConfigSource(values))

        assertEquals(2, result.size)
        assertEquals("anthropic-primary", result[0].info.id)
        assertEquals("claude-x", result[0].config.model)
        assertEquals("openai-fallback", result[1].info.id)
        assertEquals("gpt-x", result[1].config.model)
    }

    @Test
    fun `whitespace and blank entries in LLM_PROVIDER_IDS are trimmed and skipped`() {
        val values = mapOf(
            ConfigKeys.LLM_PROVIDER_IDS to "anthropic-primary, ,openai-fallback",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_PROVIDER" to "anthropic",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MODEL" to "claude-x",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TYPE" to "CLOUD",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_CONTEXT_TOKENS" to "200000",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_PROVIDER" to "openai",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_MODEL" to "gpt-x",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_TYPE" to "CLOUD",
            "DROIDCOMMAND_LLM_PROVIDER_OPENAI_FALLBACK_MAX_CONTEXT_TOKENS" to "128000",
        )
        assertEquals(2, MultiLlmConfigLoader.load(MapConfigSource(values)).size)
    }

    @Test
    fun `a hyphenated id maps to the correct upper-cased underscored key prefix`() {
        val result = MultiLlmConfigLoader.load(MapConfigSource(fullSource("anthropic-primary")))
        assertEquals("claude-x", result.single().config.model)
    }

    @Test
    fun `missing PROVIDER throws MissingConfigException`() {
        val values = fullSource() - "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_PROVIDER"
        assertFailsWith<MissingConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `missing MODEL throws MissingConfigException`() {
        val values = fullSource() - "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MODEL"
        assertFailsWith<MissingConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `missing TYPE throws MissingConfigException`() {
        val values = fullSource() - "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TYPE"
        assertFailsWith<MissingConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `missing MAX_CONTEXT_TOKENS throws MissingConfigException`() {
        val values = fullSource() - "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_CONTEXT_TOKENS"
        assertFailsWith<MissingConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `an unrecognized TYPE throws InvalidLlmProviderConfigException naming the bad value`() {
        val values = fullSource() + mapOf("DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_TYPE" to "MOON")
        val e = assertFailsWith<InvalidLlmProviderConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
        assertTrue(e.message!!.contains("MOON"))
    }

    @Test
    fun `a non-numeric MAX_CONTEXT_TOKENS throws InvalidLlmProviderConfigException`() {
        val values = fullSource() + mapOf("DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_MAX_CONTEXT_TOKENS" to "lots")
        assertFailsWith<InvalidLlmProviderConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `an unrecognized CAPABILITIES entry throws InvalidLlmProviderConfigException naming it`() {
        val values = fullSource() + mapOf("DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_CAPABILITIES" to "telepathy")
        val e = assertFailsWith<InvalidLlmProviderConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
        assertTrue(e.message!!.contains("telepathy"))
    }

    @Test
    fun `only COST_INPUT_PER_MILLION present throws InvalidLlmProviderConfigException`() {
        val values = fullSource() + mapOf("DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_COST_INPUT_PER_MILLION" to "3.0")
        assertFailsWith<InvalidLlmProviderConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `only COST_OUTPUT_PER_MILLION present throws InvalidLlmProviderConfigException`() {
        val values = fullSource() + mapOf("DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_COST_OUTPUT_PER_MILLION" to "15.0")
        assertFailsWith<InvalidLlmProviderConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `a non-numeric COST value throws InvalidLlmProviderConfigException`() {
        val values = fullSource() + mapOf(
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_COST_INPUT_PER_MILLION" to "free",
            "DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_COST_OUTPUT_PER_MILLION" to "15.0",
        )
        assertFailsWith<InvalidLlmProviderConfigException> { MultiLlmConfigLoader.load(MapConfigSource(values)) }
    }

    @Test
    fun `API_KEY re-reads the source on every authToken call, independently per provider`() {
        val values = fullSource().toMutableMap()
        val source = ConfigSource { key -> values[key] }
        val config = MultiLlmConfigLoader.load(source).single().config

        assertNull(config.authToken())

        values["DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_API_KEY"] = "secret-1"
        assertEquals("secret-1", config.authToken())

        values["DROIDCOMMAND_LLM_PROVIDER_ANTHROPIC_PRIMARY_API_KEY"] = "secret-2"
        assertEquals("secret-2", config.authToken())
    }
}
