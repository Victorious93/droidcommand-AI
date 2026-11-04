package ai.droidcommand.bots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BotRecipeTest {
    @Test
    fun `empty allowed tools means chat only`() {
        val recipe = BotRecipe(
            id = "research-bot",
            name = "Research Bot",
            personaId = "concise",
            providerType = "anthropic",
            memoryFolder = "Bots/research-bot",
        )
        assertTrue(recipe.allowedTools.isEmpty())
        assertEquals("", recipe.instructions)
        assertTrue(recipe.enabled)
    }

    @Test
    fun `fields are preserved as given`() {
        val recipe = BotRecipe(
            id = "shell-bot",
            name = "Shell Bot",
            personaId = "coder",
            instructions = "Only answer shell questions.",
            providerType = "local:llama-3.2-1b",
            allowedTools = setOf("shell_tool"),
            memoryFolder = "Bots/shell-bot",
            enabled = false,
        )
        assertEquals("shell-bot", recipe.id)
        assertEquals(setOf("shell_tool"), recipe.allowedTools)
        assertEquals("Bots/shell-bot", recipe.memoryFolder)
        assertEquals(false, recipe.enabled)
    }
}
