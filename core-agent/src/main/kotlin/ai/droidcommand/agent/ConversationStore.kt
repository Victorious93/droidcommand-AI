package ai.droidcommand.agent

/**
 * Discovery + lookup for saved [ConversationContext]s — a first, narrow
 * slice of ROADMAP-126's persistent-memory gap (one conversation's history,
 * specifically), the same shape [MacroStore] already closed for saved
 * macros. Nothing in `core-agent` reads or writes through this
 * automatically; a caller (e.g. a future `DroidCommandSession` extension)
 * decides when a conversation is saved and reloaded.
 *
 * Every implementation stores and returns an independent copy, never a
 * live reference to the [ConversationContext] passed to [save] or handed
 * back from [load] — unlike [Macro]/[MacroStep], [ConversationContext] is
 * itself mutable (`append`), so aliasing it would let a caller's continued
 * use of its own context object silently rewrite what was "saved," or let
 * mutating a loaded context corrupt the store's copy. That guarantee holds
 * automatically for [JsonFileConversationStore] (serialization always
 * produces a fresh object) and is enforced explicitly in
 * [InMemoryConversationStore].
 */
interface ConversationStore {
    fun save(conversationId: String, context: ConversationContext)

    fun load(conversationId: String): ConversationContext?

    fun list(): List<String>

    fun delete(conversationId: String): Boolean
}

/**
 * A real, immediately usable [ConversationStore] — but, like
 * [InMemoryMacroStore], it does not survive a process restart.
 * [JsonFileConversationStore] is the persistent alternative.
 */
class InMemoryConversationStore : ConversationStore {
    private val conversations = mutableMapOf<String, ConversationContext>()
    private val lock = Any()

    override fun save(conversationId: String, context: ConversationContext) {
        synchronized(lock) { conversations[conversationId] = snapshotOf(context) }
    }

    override fun load(conversationId: String): ConversationContext? =
        synchronized(lock) { conversations[conversationId]?.let { snapshotOf(it) } }

    override fun list(): List<String> = synchronized(lock) { conversations.keys.toList().sorted() }

    override fun delete(conversationId: String): Boolean = synchronized(lock) { conversations.remove(conversationId) != null }

    private fun snapshotOf(context: ConversationContext): ConversationContext {
        val copy = ConversationContext(context.systemPrompt, context.maxTokens)
        context.messages.forEach { copy.append(it) }
        return copy
    }
}
