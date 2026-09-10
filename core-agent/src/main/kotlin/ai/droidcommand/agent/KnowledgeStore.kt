package ai.droidcommand.agent

import java.time.Instant

/**
 * A single item of long-term, structured knowledge (ROADMAP-126) — a fact,
 * preference, or learned note that should outlive any one conversation.
 * Distinct from [ConversationContext] (one conversation's message history)
 * and [Macro] (a saved tool-call sequence): this is the "structured
 * knowledge and historical information" layer the master requirements
 * prompt's memory system section names separately from short-term/
 * conversation memory.
 *
 * [id] is validated the same way a [Macro] name is (see [JsonFileKnowledgeStore]).
 * [source] is free-text, caller-supplied provenance (e.g.
 * `"conversation:chat-1"`, `"manual"`) — self-declared, not independently
 * verified, the same honest posture [Initiator] already takes. Nothing in
 * this module populates [source] automatically; automatic extraction from a
 * conversation is a deliberately separate, not-yet-built slice (it needs a
 * real LLM call, which belongs in `core-llm`, not here). [tags] are exact
 * strings, case-sensitive, with no normalization.
 */
data class KnowledgeEntry(
    val id: String,
    val content: String,
    val source: String,
    val tags: Set<String> = emptySet(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
)

/**
 * Discovery + lookup for saved [KnowledgeEntry] items — the long-term-memory
 * counterpart to [MacroStore]/[ConversationStore]. [KnowledgeEntry] is
 * immutable, so (unlike [ConversationStore]) no snapshot discipline is
 * needed to avoid aliasing.
 *
 * [findByTag] and [search] are real but deliberately literal: exact,
 * case-sensitive tag matching and a case-insensitive substring match
 * against [KnowledgeEntry.content]. Neither does, or claims to do,
 * semantic/embedding search — there is no LLM or embedding call anywhere
 * on this path. Both are honest O(n) scans, not backed by any index; that
 * is an acceptable cost at the scale a single agent's knowledge base is
 * expected to reach, and a real index is a named future slice, not
 * something this interface pretends to already have.
 */
interface KnowledgeStore {
    fun save(entry: KnowledgeEntry)

    fun load(id: String): KnowledgeEntry?

    fun list(): List<String>

    fun delete(id: String): Boolean

    fun findByTag(tag: String): List<KnowledgeEntry>

    fun search(keyword: String): List<KnowledgeEntry>
}

/**
 * A real, immediately usable [KnowledgeStore] — but, like
 * [InMemoryMacroStore]/[InMemoryConversationStore], it does not survive a
 * process restart. [JsonFileKnowledgeStore] is the persistent alternative.
 */
class InMemoryKnowledgeStore : KnowledgeStore {
    private val entries = mutableMapOf<String, KnowledgeEntry>()
    private val lock = Any()

    override fun save(entry: KnowledgeEntry) {
        synchronized(lock) { entries[entry.id] = entry }
    }

    override fun load(id: String): KnowledgeEntry? = synchronized(lock) { entries[id] }

    override fun list(): List<String> = synchronized(lock) { entries.keys.toList().sorted() }

    override fun delete(id: String): Boolean = synchronized(lock) { entries.remove(id) != null }

    override fun findByTag(tag: String): List<KnowledgeEntry> =
        synchronized(lock) { entries.values.filter { tag in it.tags }.sortedBy { it.id } }

    override fun search(keyword: String): List<KnowledgeEntry> =
        synchronized(lock) { entries.values.filter { it.content.contains(keyword, ignoreCase = true) }.sortedBy { it.id } }
}
