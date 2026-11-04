package ai.droidcommand.bots

import ai.droidcommand.agent.PersonaStore
import ai.droidcommand.agent.ToolRunner

/** [BotRunner.compose] couldn't find the recipe's [BotRecipe.personaId] in the [PersonaStore] it was given. */
class UnknownBotPersona(personaId: String, botId: String) : IllegalStateException(
    "Bot '$botId' references persona '$personaId', which is not in the persona store",
)

/**
 * Ties a [BotRecipe] to one execution, per `docs/BRAIN_JARVIS_BOTMAKER_PLAN.md`
 * §3's "Sandbox" section. Deliberately does **not** build a `ChatSession` or
 * `DroidCommandSession` itself — those already take a [ToolRunner] /
 * system-prompt-shaped input at their existing call sites in
 * `core-llm-factory` / `core-agent`. This type only produces the two pieces
 * a caller needs to run a bot through that existing machinery: the scoped
 * [ToolRunner] ([scopedExecutor]) and the composed system prompt
 * ([compose]). Keeping `core-bots` free of a `core-llm-factory` dependency
 * mirrors `ai.droidcommand.agent.Persona`'s own stated reason for staying
 * free of `core-security`: a one-way dependency arrow, not a dependency
 * cycle.
 */
object BotRunner {
    /** A bot can only call the tools its own recipe lists — see [ScopedToolRunner]. */
    fun scopedExecutor(recipe: BotRecipe, delegate: ToolRunner): ToolRunner =
        ScopedToolRunner(delegate, recipe.allowedTools)

    /**
     * The system prompt for one run of [recipe]: the referenced persona's
     * [ai.droidcommand.agent.Persona.contextContribution] (its communication
     * style — see that type's own "Critical Isolation" doc, unchanged here)
     * followed by this bot's own extra [BotRecipe.instructions], if any.
     * Throws [UnknownBotPersona] rather than silently falling back to no
     * persona, so a bot never runs under an unexpectedly bare prompt.
     */
    fun compose(recipe: BotRecipe, personas: PersonaStore): String {
        val persona = personas.load(recipe.personaId) ?: throw UnknownBotPersona(recipe.personaId, recipe.id)
        val style = persona.contextContribution
        return if (recipe.instructions.isBlank()) style else "$style\n\n${recipe.instructions}"
    }
}
