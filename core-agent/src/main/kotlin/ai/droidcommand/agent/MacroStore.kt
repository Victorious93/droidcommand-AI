package ai.droidcommand.agent

/**
 * Discovery + lookup for saved [Macro]s — the storage half of ROADMAP-127
 * (a routine has to be savable somewhere before [MacroExecutor] can play it
 * back later) and a first, narrow slice of ROADMAP-126's persistent-memory
 * gap. Scheduling — actually triggering a saved macro on a timer/event — is
 * a separate, still-open concern this interface does not address.
 */
interface MacroStore {
    fun save(macro: Macro)

    fun load(name: String): Macro?

    fun list(): List<String>

    fun delete(name: String): Boolean
}

/**
 * A real, immediately usable [MacroStore] — but, like core-security's
 * `InMemoryGrantStore`/`InMemoryAuditLog`, it does not survive a process
 * restart. [JsonFileMacroStore] is the persistent alternative.
 */
class InMemoryMacroStore : MacroStore {
    private val macros = mutableMapOf<String, Macro>()
    private val lock = Any()

    override fun save(macro: Macro) {
        synchronized(lock) { macros[macro.name] = macro }
    }

    override fun load(name: String): Macro? = synchronized(lock) { macros[name] }

    override fun list(): List<String> = synchronized(lock) { macros.keys.toList().sorted() }

    override fun delete(name: String): Boolean = synchronized(lock) { macros.remove(name) != null }
}
