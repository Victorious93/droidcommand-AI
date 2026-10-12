package ai.droidcommand.llm.factory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AiSlashCommandsTest {
    @Test
    fun `each command sends to its provider and keeps the prompt intact`() {
        assertEquals(AiSlashCommands.Parsed.Command("anthropic", "summarise this"), AiSlashCommands.parse("/claude summarise this"))
        assertEquals(AiSlashCommands.Parsed.Command("openai", "hi"), AiSlashCommands.parse("/chatgpt hi"))
        assertEquals(AiSlashCommands.Parsed.Command("google", "hi"), AiSlashCommands.parse("/gemini hi"))
        assertEquals(AiSlashCommands.Parsed.Command("groq", "hi"), AiSlashCommands.parse("/groq hi"))
    }

    @Test
    fun `the command name ignores case and the prompt may span lines`() {
        assertEquals(
            AiSlashCommands.Parsed.Command("anthropic", "line one\nline two"),
            AiSlashCommands.parse("  /CLAUDE line one\nline two  "),
        )
    }

    @Test
    fun `a known command with no prompt is reported, not sent`() {
        assertEquals(AiSlashCommands.Parsed.MissingPrompt("claude"), AiSlashCommands.parse("/claude   "))
        assertEquals(AiSlashCommands.Parsed.MissingPrompt("chatgpt"), AiSlashCommands.parse("/chatgpt"))
    }

    @Test
    fun `text that is not a known AI command stays ordinary text`() {
        assertEquals(AiSlashCommands.Parsed.NotCommand, AiSlashCommands.parse("hello"))
        assertEquals(AiSlashCommands.Parsed.NotCommand, AiSlashCommands.parse("/usr/bin/env ls"))
        assertEquals(AiSlashCommands.Parsed.NotCommand, AiSlashCommands.parse("/"))
        assertEquals(AiSlashCommands.Parsed.NotCommand, AiSlashCommands.parse("claude hi"))
    }

    @Test
    fun `every command names a provider that exists in the catalog`() {
        assertEquals(listOf("claude", "chatgpt", "gemini", "groq"), AiSlashCommands.commandNames)
        AiSlashCommands.commandNames.forEach { name ->
            val parsed = assertIs<AiSlashCommands.Parsed.Command>(AiSlashCommands.parse("/$name hi"))
            assertNotNull(CloudProviderCatalog.byId(parsed.providerId))
        }
    }
}
