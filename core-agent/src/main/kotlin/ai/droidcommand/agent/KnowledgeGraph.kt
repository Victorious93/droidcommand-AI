package ai.droidcommand.agent

/**
 * The 15 entity types `docs/CAPABILITY_ROADMAP_PROMPT.md`'s P0.7 names,
 * verbatim — unlike every other CAP-00x entity/category list, this one
 * has no trailing "etc.," so all 15 are included rather than a guessed
 * subset.
 */
enum class EntityType {
    NOTE,
    AUTOMATION,
    DEVICE,
    COMMAND,
    LOG,
    PROJECT,
    CONCEPT,
    VARIABLE,
    PLUGIN,
    AI_CONTEXT,
    DOCUMENT,
    CONVERSATION,
    PERSONA,
    TASK,
    EXECUTION_TARGET,
}

/**
 * A node in the knowledge graph (CAP-007, P0.7). [properties] is a
 * generic string map — the same shape `LogEvent.fields`/`AuditEvent.detail`
 * already use elsewhere in this codebase — a deliberate choice over 15
 * speculative typed schemas (one per [EntityType]) with no real data
 * model to justify specific fields for most of them.
 */
data class GraphNode(
    val id: String,
    val type: EntityType,
    val label: String,
    val properties: Map<String, String> = emptyMap(),
)

/**
 * A directed edge between two [GraphNode]s. [relationship] is free text
 * (e.g. `"depends_on"`, `"created_by"`), not a fixed enum: the roadmap
 * prompt names no relationship taxonomy, and inventing one would be
 * exactly the kind of fabrication this codebase's rules forbid — the same
 * "free text, no normalization" posture [KnowledgeEntry.tags] already
 * takes.
 */
data class GraphEdge(
    val id: String,
    val fromId: String,
    val toId: String,
    val relationship: String,
    val properties: Map<String, String> = emptyMap(),
)

/** Which direction(s) of a [GraphEdge] touching a node count as a match — see [KnowledgeGraphStore.edges]. */
enum class EdgeDirection { OUTGOING, INCOMING, BOTH }

/**
 * Discovery + lookup for a graph of [GraphNode]s/[GraphEdge]s — additive
 * alongside [KnowledgeStore] (a flat id/tag/content store), not a
 * replacement for it.
 *
 * **Referential integrity is deliberately not enforced by [saveEdge]** —
 * no `*Store` anywhere in this codebase validates cross-references
 * ([MacroStore] doesn't check a referenced tool exists;
 * [ConversationStore] validates nothing about content); this is a storage
 * primitive, not a validated-construction API like [TaskGraph.from]
 * (which serves a narrower, already-solved domain: task dependency graphs
 * specifically). A caller wanting validated construction can [loadNode]
 * first themselves.
 */
interface KnowledgeGraphStore {
    fun saveNode(node: GraphNode)

    fun loadNode(id: String): GraphNode?

    /**
     * Removes the node. When [cascade] (default `true`), every [GraphEdge]
     * touching it is removed too — the sane default a graph consumer
     * expects, avoiding silently orphaned edges. `cascade = false` leaves
     * those edges in place (e.g. a caller pending re-linking them to a
     * replacement node) — a subsequent [loadNode] through such an edge
     * honestly returns `null` rather than a fabricated node.
     */
    fun deleteNode(id: String, cascade: Boolean = true): Boolean

    fun listNodes(type: EntityType? = null): List<String>

    fun saveEdge(edge: GraphEdge)

    fun loadEdge(id: String): GraphEdge?

    fun deleteEdge(id: String): Boolean

    /** Every [GraphEdge] touching [nodeId] in the given [direction], optionally filtered to one [relationship]. */
    fun edges(nodeId: String, relationship: String? = null, direction: EdgeDirection = EdgeDirection.BOTH): List<GraphEdge>
}

/** The real, immediately usable [KnowledgeGraphStore] — does not survive a process restart, matching [InMemoryKnowledgeStore]'s exact precedent. */
class InMemoryKnowledgeGraphStore : KnowledgeGraphStore {
    private val nodes = mutableMapOf<String, GraphNode>()
    private val edgesById = mutableMapOf<String, GraphEdge>()
    private val lock = Any()

    override fun saveNode(node: GraphNode) {
        synchronized(lock) { nodes[node.id] = node }
    }

    override fun loadNode(id: String): GraphNode? = synchronized(lock) { nodes[id] }

    override fun deleteNode(id: String, cascade: Boolean): Boolean =
        synchronized(lock) {
            val existed = nodes.remove(id) != null
            if (existed && cascade) {
                edgesById.values.filter { it.fromId == id || it.toId == id }.forEach { edgesById.remove(it.id) }
            }
            existed
        }

    override fun listNodes(type: EntityType?): List<String> =
        synchronized(lock) { nodes.values.filter { type == null || it.type == type }.map { it.id }.sorted() }

    override fun saveEdge(edge: GraphEdge) {
        synchronized(lock) { edgesById[edge.id] = edge }
    }

    override fun loadEdge(id: String): GraphEdge? = synchronized(lock) { edgesById[id] }

    override fun deleteEdge(id: String): Boolean = synchronized(lock) { edgesById.remove(id) != null }

    override fun edges(nodeId: String, relationship: String?, direction: EdgeDirection): List<GraphEdge> =
        synchronized(lock) {
            edgesById.values.filter { edge ->
                matchesDirection(edge, nodeId, direction) && (relationship == null || edge.relationship == relationship)
            }.sortedBy { it.id }
        }

    private fun matchesDirection(edge: GraphEdge, nodeId: String, direction: EdgeDirection): Boolean = when (direction) {
        EdgeDirection.OUTGOING -> edge.fromId == nodeId
        EdgeDirection.INCOMING -> edge.toId == nodeId
        EdgeDirection.BOTH -> edge.fromId == nodeId || edge.toId == nodeId
    }
}

/** Every node directly connected to [nodeId] by a matching edge (one hop) — [loadNode] misses (a dangling edge) are silently skipped, never fabricated. */
fun KnowledgeGraphStore.neighbors(
    nodeId: String,
    relationship: String? = null,
    direction: EdgeDirection = EdgeDirection.BOTH,
): List<GraphNode> =
    edges(nodeId, relationship, direction)
        .map { edge -> if (edge.fromId == nodeId) edge.toId else edge.fromId }
        .distinct()
        .mapNotNull(::loadNode)

/**
 * Every node reachable from [nodeId] within [maxHops] edges (a bounded
 * breadth-first search), optionally following only edges matching
 * [relationship]. **This is CAP-007/P0.7's own "use graph-based retrieval
 * where useful instead of indiscriminately loading all stored
 * information," made concrete:** only nodes actually connected within
 * [maxHops] are ever touched, never the whole store. Deterministic: a
 * node is visited at most once, so a cycle can never loop forever or
 * produce a duplicate.
 */
fun KnowledgeGraphStore.relatedWithinHops(nodeId: String, maxHops: Int, relationship: String? = null): List<GraphNode> {
    require(maxHops >= 1) { "maxHops must be >= 1, got $maxHops" }

    val visited = mutableSetOf(nodeId)
    val result = mutableListOf<GraphNode>()
    var frontier = listOf(nodeId)

    repeat(maxHops) {
        val nextFrontier = mutableListOf<String>()
        for (id in frontier) {
            for (edge in edges(id, relationship, EdgeDirection.BOTH)) {
                val neighborId = if (edge.fromId == id) edge.toId else edge.fromId
                if (visited.add(neighborId)) {
                    loadNode(neighborId)?.let { result += it }
                    nextFrontier += neighborId
                }
            }
        }
        frontier = nextFrontier
    }

    return result
}
