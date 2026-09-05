package ai.droidcommand.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LlmConfigLoaderTest {
    @Test
    fun `loads provider and model from the source`() {
        val config = LlmConfigLoader.load(
            MapConfigSource(mapOf("DROIDCOMMAND_LLM_PROVIDER" to "anthropic", "DROIDCOMMAND_LLM_MODEL" to "claude-x")),
        )
        assertEquals("anthropic", config.provider)
        assertEquals("claude-x", config.model)
    }

    @Test
    fun `throws MissingConfigException when provider is absent`() {
        assertFailsWith<MissingConfigException> {
            LlmConfigLoader.load(MapConfigSource(mapOf("DROIDCOMMAND_LLM_MODEL" to "claude-x")))
        }
    }

    @Test
    fun `throws MissingConfigException when model is absent`() {
        assertFailsWith<MissingConfigException> {
            LlmConfigLoader.load(MapConfigSource(mapOf("DROIDCOMMAND_LLM_PROVIDER" to "anthropic")))
        }
    }

    @Test
    fun `optional fields default to null when absent and parse when present`() {
        val minimal = LlmConfigLoader.load(
            MapConfigSource(mapOf("DROIDCOMMAND_LLM_PROVIDER" to "anthropic", "DROIDCOMMAND_LLM_MODEL" to "claude-x")),
        )
        assertNull(minimal.endpoint)
        assertNull(minimal.temperature)
        assertNull(minimal.maxOutputTokens)

        val full = LlmConfigLoader.load(
            MapConfigSource(
                mapOf(
                    "DROIDCOMMAND_LLM_PROVIDER" to "anthropic",
                    "DROIDCOMMAND_LLM_MODEL" to "claude-x",
                    "DROIDCOMMAND_LLM_ENDPOINT" to "https://example.invalid",
                    "DROIDCOMMAND_LLM_TEMPERATURE" to "0.4",
                    "DROIDCOMMAND_LLM_MAX_OUTPUT_TOKENS" to "2048",
                ),
            ),
        )
        assertEquals("https://example.invalid", full.endpoint)
        assertEquals(0.4, full.temperature)
        assertEquals(2048, full.maxOutputTokens)
    }

    @Test
    fun `authToken re-reads the source on every call rather than capturing a value at load time`() {
        val values = mutableMapOf("DROIDCOMMAND_LLM_PROVIDER" to "anthropic", "DROIDCOMMAND_LLM_MODEL" to "claude-x")
        val source = ConfigSource { key -> values[key] }
        val config = LlmConfigLoader.load(source)

        assertNull(config.authToken())

        values["DROIDCOMMAND_LLM_API_KEY"] = "secret-1"
        assertEquals("secret-1", config.authToken())

        values["DROIDCOMMAND_LLM_API_KEY"] = "secret-2"
        assertEquals("secret-2", config.authToken())
    }
}
