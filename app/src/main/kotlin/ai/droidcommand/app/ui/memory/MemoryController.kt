package ai.droidcommand.app.ui.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.KnowledgeGraph

/**
 * The Android-free core of the "view and delete what the app remembers" screen (knowledge-graph K4):
 * thin operations over an already-tested [KnowledgeGraph], so the logic is unit-testable on the JVM
 * without Android or Compose. [MemoryViewModel] wraps this for state and threading.
 *
 * "List all" is [KnowledgeGraph.searchEntities] with an empty keyword — an empty substring matches every
 * label in both the in-memory and Room implementations (covered by their own tests). Entities are
 * presented grouped by type then label, so the same screen reads the same way run to run.
 */
class MemoryController(private val graph: KnowledgeGraph) {
    fun all(): List<Entity> =
        graph.searchEntities("").sortedWith(compareBy({ it.type.name }, { it.label.lowercase() }, { it.id }))

    /** Removes one entity (and, by the graph's own cascade, its relationships). Returns whether it existed. */
    fun delete(id: String): Boolean = graph.removeEntity(id)

    /** Removes everything. Returns how many entities were removed. Relationships cascade with their entities. */
    fun clearAll(): Int {
        val ids = graph.searchEntities("").map { it.id }
        return ids.count { graph.removeEntity(it) }
    }
}
