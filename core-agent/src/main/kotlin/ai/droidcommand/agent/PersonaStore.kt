package ai.droidcommand.agent

/**
 * Discovery + lookup for saved [Persona]s — the same shape
 * [KnowledgeStore]/[MacroStore]/[ConversationStore] already establish.
 * [Persona] is immutable, so (like [KnowledgeStore]) no snapshot
 * discipline is needed to avoid aliasing.
 *
 * [setEnabled] is the mechanical implementation of P0.5's
 * `PersonaManager.setActivePersona`: a real load→copy→save, not a
 * separate lifecycle-state store — returns `false` for an unknown [id]
 * rather than throwing, the same "report, don't crash" shape
 * [MacroExecutor]'s unknown-tool handling already uses.
 */
interface PersonaStore {
    fun save(persona: Persona)

    fun load(id: String): Persona?

    fun list(): List<String>

    fun delete(id: String): Boolean

    fun setEnabled(id: String, enabled: Boolean): Boolean
}

/**
 * A real, immediately usable [PersonaStore] — but, like
 * [InMemoryKnowledgeStore], it does not survive a process restart.
 * [JsonFilePersonaStore] is the persistent alternative.
 */
class InMemoryPersonaStore : PersonaStore {
    private val personas = mutableMapOf<String, Persona>()
    private val lock = Any()

    override fun save(persona: Persona) {
        synchronized(lock) { personas[persona.id] = persona }
    }

    override fun load(id: String): Persona? = synchronized(lock) { personas[id] }

    override fun list(): List<String> = synchronized(lock) { personas.keys.toList().sorted() }

    override fun delete(id: String): Boolean = synchronized(lock) { personas.remove(id) != null }

    override fun setEnabled(id: String, enabled: Boolean): Boolean =
        synchronized(lock) {
            val existing = personas[id] ?: return false
            personas[id] = existing.copy(enabled = enabled)
            true
        }
}
