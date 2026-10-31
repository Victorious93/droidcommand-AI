package ai.droidcommand.bots

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Formality
import ai.droidcommand.agent.HumorProfile
import ai.droidcommand.agent.InMemoryPersonaStore
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.Persona
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.agent.StructureProfile
import ai.droidcommand.agent.StyleProfile
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.Verbosity
import ai.droidcommand.agent.VocabProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun testPersona(id: String, contribution: String) = Persona(
    id = id,
    name = "Test Persona",
    category = PersonaCategory.CUSTOM,
    sourceConversations = emptyList(),
    styleCharacteristics = StyleProfile(
        tone = "concise",
        vocabulary = VocabProfile("simple"),
        sentenceStructure = StructureProfile("short", "minimal"),
        formality = Formality.NEUTRAL,
        verbosity = Verbosity.CONCISE,
        humor = HumorProfile(false),
        responseStructure = "plain",
    ),
    contextContribution = contribution,
    version = "1",
    enabled = true,
)

private class EchoToolRunner : ToolRunner {
    override fun run(
        toolName: String,
        input: Map<String, String>,
        retryPolicy: RetryPolicy,
        isCancelled: () -> Boolean,
        mode: AgentMode?,
        initiator: Initiator?,
        grantId: String?,
    ): ToolResult = ToolResult.Success(toolName)
}

class BotRunnerTest {
    @Test
    fun `scopedExecutor restricts the given delegate to the recipe's allowed tools`() {
        val recipe = BotRecipe(
            id = "b1",
            name = "Bot",
            personaId = "p1",
            providerType = "anthropic",
            allowedTools = setOf("allowed_tool"),
            memoryFolder = "Bots/b1",
        )

        val scoped = BotRunner.scopedExecutor(recipe, EchoToolRunner())

        val allowed = scoped.run("allowed_tool", emptyMap())
        val denied = scoped.run("other_tool", emptyMap())
        assertEquals(ToolResult.Success("allowed_tool"), allowed)
        assertTrue(denied is ToolResult.Failure)
    }

    @Test
    fun `compose concatenates persona style and bot instructions`() {
        val personas = InMemoryPersonaStore()
        personas.save(testPersona("p1", "Write concisely."))
        val recipe = BotRecipe(
            id = "b1",
            name = "Bot",
            personaId = "p1",
            instructions = "Only answer research questions.",
            providerType = "anthropic",
            memoryFolder = "Bots/b1",
        )

        val prompt = BotRunner.compose(recipe, personas)

        assertEquals("Write concisely.\n\nOnly answer research questions.", prompt)
    }

    @Test
    fun `compose with no extra instructions returns only the persona style`() {
        val personas = InMemoryPersonaStore()
        personas.save(testPersona("p1", "Write concisely."))
        val recipe = BotRecipe(
            id = "b1",
            name = "Bot",
            personaId = "p1",
            providerType = "anthropic",
            memoryFolder = "Bots/b1",
        )

        assertEquals("Write concisely.", BotRunner.compose(recipe, personas))
    }

    @Test
    fun `compose throws a clear error for a missing persona rather than a null pointer`() {
        val personas = InMemoryPersonaStore()
        val recipe = BotRecipe(
            id = "b1",
            name = "Bot",
            personaId = "missing-persona",
            providerType = "anthropic",
            memoryFolder = "Bots/b1",
        )

        val e = assertFailsWith<UnknownBotPersona> { BotRunner.compose(recipe, personas) }
        assertTrue(e.message!!.contains("missing-persona"))
    }
}
