package ai.droidcommand.agent

import java.time.Instant

/**
 * The closed set of entity kinds `docs/CAPABILITY_ROADMAP_PROMPT.md`'s P0.7
 * (CAP-007) lists as "potential entities" — mapped 1:1 from that list, no
 * additions and no omissions, the same restraint [ContextKind] already
 * applies toward the source list it maps.
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
 * One typed node in a [KnowledgeGraph] (ROADMAP-126 / CAP-007) — the piece
 * [KnowledgeStore] was documented as missing: a flat [KnowledgeEntry] has no
 * [type] and no way to relate to another entry. [properties] is deliberately
 * an open string map rather than a per-[EntityType] fixed shape: the spec
 * gives no per-type field list, and inventing one would fabricate structure
 * the roadmap never asked for. An [Entity] may reference an existing
 * [KnowledgeEntry]/[Persona]/[Task]/conversation id via [properties] (e.g.
 * `properties["knowledgeEntryId"]`) at the caller's own discretion — this
 * module does not enforce or automatically maintain such a link, the same
 * "compose, don't duplicate" restraint [Persona.sourceConversations]
 * already takes toward [ConversationStore] ids.
 */
data class Entity(
    val id: String,
    val type: EntityType,
    val label: String,
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = createdAt,
)

/**
 * A directed, typed edge between two [Entity] ids. [type] is a free string
 * (e.g. `"depends_on"`, `"mentions"`, `"runs_on"`) rather than a closed enum
 * — the spec defines no relationship-type taxonomy, and [Relationship.type]
 * mirrors [KnowledgeEntry.tags]/[KnowledgeEntry.source] in staying free-text
 * rather than inventing one. Not required to form a DAG: unlike [TaskGraph],
 * a knowledge graph's edges may legitimately cycle (e.g. two [CONCEPT]
 * entities that reference each other).
 */
data class Relationship(
    val id: String,
    val fromId: String,
    val toId: String,
    val type: String,
    val properties: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
)

/** Thrown by [KnowledgeGraph.addRelationship] when [Relationship.fromId] or [Relationship.toId] does not resolve to a saved [Entity] — a dangling edge would silently break traversal, so this is rejected at write time rather than left representable. */
class UnknownEntityException(val id: String) : IllegalArgumentException("No entity with id '$id' exists")

/**
 * A structured, typed alternative to [KnowledgeStore]'s flat id/tag/content
 * model (CAP-007, P0.7): [Entity] nodes of a known [EntityType], connected
 * by typed [Relationship] edges, queryable by traversal rather than only by
 * a full scan. [searchEntities] stays the same explicitly non-semantic,
 * literal-substring retrieval [KnowledgeStore.search] already documents for
 * itself — no embedding/semantic matching exists anywhere on this path.
 *
 * [traverse] is this interface's answer to P0.7's own "use graph-based
 * retrieval where useful instead of indiscriminately loading all stored
 * information": a caller starting from one relevant entity gets back only
 * what's within [Int] hops of it, not every entity ever stored.
 */
interface KnowledgeGraph {
    fun addEntity(entity: Entity)

    fun getEntity(id: String): Entity?

    /** Removes the entity, and cascades: every [Relationship] touching [id] (either direction) is removed too, so no dangling edge survives. Returns whether the entity existed. */
    fun removeEntity(id: String): Boolean

    fun entitiesByType(type: EntityType): List<Entity>

    fun searchEntities(keyword: String): List<Entity>

    /** @throws UnknownEntityException if [Relationship.fromId] or [Relationship.toId] doesn't resolve to a saved [Entity]. */
    fun addRelationship(relationship: Relationship)

    fun removeRelationship(id: String): Boolean

    fun relationshipsFrom(entityId: String): List<Relationship>

    fun relationshipsTo(entityId: String): List<Relationship>

    /** One hop from [entityId], direction-agnostic (an edge either originating or terminating at [entityId] counts), optionally filtered to [relationshipType]. */
    fun neighbors(entityId: String, relationshipType: String? = null): List<Entity>

    /**
     * Breadth-first traversal outward from [startId], up to [maxDepth] hops,
     * direction-agnostic per hop (see [neighbors]), optionally filtered to
     * [relationshipType]. Excludes [startId] itself, visits each entity at
     * most once (so a relationship cycle can never loop this), and returns
     * entities in BFS discovery order — deterministic across calls given
     * identical graph state, the same determinism discipline [TaskGraph.from]
     * already documents for its own traversal.
     */
    fun traverse(startId: String, maxDepth: Int, relationshipType: String? = null): List<Entity>
}

/**
 * A real, immediately usable [KnowledgeGraph] — but, like
 * [InMemoryKnowledgeStore], it does not survive a process restart.
 * [JsonFileKnowledgeGraph] is the persistent alternative.
 */
class InMemoryKnowledgeGraph : KnowledgeGraph {
    private val entities = mutableMapOf<String, Entity>()
    private val relationships = mutableMapOf<String, Relationship>()
    private val lock = Any()

    override fun addEntity(entity: Entity) {
        synchronized(lock) { entities[entity.id] = entity }
    }

    override fun getEntity(id: String): Entity? = synchronized(lock) { entities[id] }

    override fun removeEntity(id: String): Boolean = synchronized(lock) {
        val existed = entities.remove(id) != null
        if (existed) {
            relationships.values
                .filter { it.fromId == id || it.toId == id }
                .forEach { relationships.remove(it.id) }
        }
        existed
    }

    override fun entitiesByType(type: EntityType): List<Entity> =
        synchronized(lock) { entities.values.filter { it.type == type }.sortedBy { it.id } }

    override fun searchEntities(keyword: String): List<Entity> =
        synchronized(lock) { entities.values.filter { it.label.contains(keyword, ignoreCase = true) }.sortedBy { it.id } }

    override fun addRelationship(relationship: Relationship) {
        synchronized(lock) {
            if (relationship.fromId !in entities) throw UnknownEntityException(relationship.fromId)
            if (relationship.toId !in entities) throw UnknownEntityException(relationship.toId)
            relationships[relationship.id] = relationship
        }
    }

    override fun removeRelationship(id: String): Boolean = synchronized(lock) { relationships.remove(id) != null }

    override fun relationshipsFrom(entityId: String): List<Relationship> =
        synchronized(lock) { relationships.values.filter { it.fromId == entityId }.sortedBy { it.id } }

    override fun relationshipsTo(entityId: String): List<Relationship> =
        synchronized(lock) { relationships.values.filter { it.toId == entityId }.sortedBy { it.id } }

    override fun neighbors(entityId: String, relationshipType: String?): List<Entity> = synchronized(lock) {
        neighborsLocked(entityId, relationshipType)
    }

    private fun neighborsLocked(entityId: String, relationshipType: String?): List<Entity> {
        val neighborIds = relationships.values
            .asSequence()
            .filter { relationshipType == null || it.type == relationshipType }
            .filter { it.fromId == entityId || it.toId == entityId }
            .map { if (it.fromId == entityId) it.toId else it.fromId }
            .toSet()
        return neighborIds.mapNotNull { entities[it] }.sortedBy { it.id }
    }

    override fun traverse(startId: String, maxDepth: Int, relationshipType: String?): List<Entity> = synchronized(lock) {
        require(maxDepth >= 0) { "maxDepth must be >= 0, got $maxDepth" }
        val visited = mutableSetOf(startId)
        val result = mutableListOf<Entity>()
        var frontier = listOf(startId)
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth) {
            val next = mutableListOf<String>()
            for (id in frontier) {
                for (neighbor in neighborsLocked(id, relationshipType)) {
                    if (visited.add(neighbor.id)) {
                        result += neighbor
                        next += neighbor.id
                    }
                }
            }
            frontier = next
            depth++
        }
        result
    }
}
