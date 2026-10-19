package ai.droidcommand.app.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.KnowledgeGraph
import java.time.Instant

/**
 * The view/edit/delete half of knowledge-graph phase K4 (`docs/KNOWLEDGE_GRAPH_PHASE_SCOPE.md`). Deliberately
 * Android-free — it touches nothing but [KnowledgeGraph] — so it is plain-JUnit-testable without
 * Robolectric, the same reasoning [ai.droidcommand.llm.factory.ChatSession] already documents for staying
 * off Android types. [MemoryViewModel] is the only caller; it does the threading.
 *
 * "List all" has no dedicated [KnowledgeGraph] method, so [all] uses [KnowledgeGraph.searchEntities] with
 * an empty keyword — every label contains the empty string, so this returns every entity. Relationships
 * are not surfaced here: K4's UI reference (`Victorious93/opendroid`'s `MemoryScreen`) only lists facts,
 * and a stray edge pointing at a fact a user already deleted would be confusing to show on its own.
 */
class MemoryController(private val graph: KnowledgeGraph) {
    /** Every remembered entity, most recently updated first (so a fresh "remember this chat" is visible without scrolling). */
    fun all(): List<Entity> = graph.searchEntities("").sortedByDescending { it.updatedAt }

    /** Deletes one entity. Cascades to any relationship touching it, per [KnowledgeGraph.removeEntity]. Returns whether it existed. */
    fun delete(id: String): Boolean = graph.removeEntity(id)

    /**
     * Changes an entity's label and bumps its [Entity.updatedAt]. Type, properties and relationships are kept
     * ([KnowledgeGraph.addEntity] replaces the entity by id and does not touch edges). Returns the updated
     * entity, or `null` when [id] does not exist or [newLabel] is blank. An edit is the user's own correction,
     * so it is stored as given.
     */
    fun rename(id: String, newLabel: String): Entity? {
        val existing = graph.getEntity(id) ?: return null
        return update(id, newLabel, existing.type, existing.properties)
    }

    /**
     * Replaces an entity's label, type and properties and bumps [Entity.updatedAt] when anything changed.
     * Relationships are kept. Keys are trimmed and blank keys dropped; values are trimmed. Returns the stored
     * entity, or `null` when [id] does not exist or [label] is blank (nothing is changed then).
     */
    fun update(id: String, label: String, type: EntityType, properties: Map<String, String>): Entity? {
        val newLabel = label.trim()
        if (newLabel.isEmpty()) return null
        val existing = graph.getEntity(id) ?: return null
        val cleaned = properties.entries
            .map { (k, v) -> k.trim() to v.trim() }
            .filter { (k, _) -> k.isNotEmpty() }
            .toMap()
        if (existing.label == newLabel && existing.type == type && existing.properties == cleaned) return existing
        val updated = existing.copy(label = newLabel, type = type, properties = cleaned, updatedAt = Instant.now())
        graph.addEntity(updated)
        return updated
    }

    /** Deletes everything. Returns how many entities were removed. */
    fun clearAll(): Int {
        val ids = graph.searchEntities("").map { it.id }
        ids.forEach(graph::removeEntity)
        return ids.size
    }

    companion object {
        /** One `key=value` per line, split on the first `=`. Lines without `=` or with a blank key are ignored; a repeated key keeps the last value. */
        fun parseProperties(text: String): Map<String, String> =
            text.lines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim().takeIf { it.isNotEmpty() }?.let { it to line.substring(i + 1).trim() }
            }.toMap()

        /** The inverse of [parseProperties], one `key=value` per line in key order. */
        fun formatProperties(properties: Map<String, String>): String =
            properties.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
    }
}
