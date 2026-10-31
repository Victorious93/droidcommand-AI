package ai.droidcommand.bots

/**
 * Discovery + lookup for saved [BotRecipe]s — the same shape
 * `ai.droidcommand.agent.PersonaStore` already establishes for personas.
 * [BotRecipe] is treated as immutable (replace via [save] under the same
 * id), so no snapshot discipline is needed.
 */
interface BotStore {
    fun save(recipe: BotRecipe)

    fun load(id: String): BotRecipe?

    fun list(): List<String>

    fun delete(id: String): Boolean
}

/**
 * A real, immediately usable [BotStore] — but, like
 * `ai.droidcommand.agent.InMemoryPersonaStore`, it does not survive a
 * process restart. [JsonFileBotStore] is the persistent alternative.
 */
class InMemoryBotStore : BotStore {
    private val recipes = mutableMapOf<String, BotRecipe>()
    private val lock = Any()

    override fun save(recipe: BotRecipe) {
        synchronized(lock) { recipes[recipe.id] = recipe }
    }

    override fun load(id: String): BotRecipe? = synchronized(lock) { recipes[id] }

    override fun list(): List<String> = synchronized(lock) { recipes.keys.toList().sorted() }

    override fun delete(id: String): Boolean = synchronized(lock) { recipes.remove(id) != null }
}
