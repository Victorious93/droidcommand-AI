package ai.droidcommand.knowledge.android

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Relationship
import ai.droidcommand.agent.UnknownEntityException
import android.content.Context
import androidx.room.Room
import java.time.Instant

/**
 * Room-backed [KnowledgeGraph] with the same observable behavior as
 * [ai.droidcommand.agent.InMemoryKnowledgeGraph] (checked by a differential test): results sorted by
 * id, direction-agnostic [neighbors]/[traverse], saving an entity or relationship under an existing id
 * replaces it, removing an entity removes every edge touching it.
 *
 * Differences worth knowing:
 * - [searchEntities] is a literal, case-insensitive substring match done in Kotlin over all entities
 *   (O(n)). SQLite's LIKE/lower() only fold ASCII, so doing it in SQL would change the results for
 *   non-ASCII labels. Everything else (neighbors, traversal, type lookup, edge lookup) is indexed.
 * - An entity type stored by a newer build that this build does not know reads back as
 *   [EntityType.CONCEPT] instead of crashing.
 * - Methods are synchronous like the interface; call them off the main thread.
 */
class RoomKnowledgeGraph(private val database: KnowledgeDatabase) : KnowledgeGraph {
    private val dao = database.dao()

    override fun addEntity(entity: Entity) {
        database.runInTransaction {
            dao.upsertEntity(entity.toRow())
            dao.deleteEntityProperties(entity.id)
            dao.insertEntityProperties(entity.properties.map { (k, v) -> EntityPropertyRow(entity.id, k, v) })
        }
    }

    override fun getEntity(id: String): Entity? = database.runInTransaction<Entity?> {
        dao.entity(id)?.let { hydrate(listOf(it)).single() }
    }

    override fun removeEntity(id: String): Boolean = dao.deleteEntity(id) > 0

    override fun entitiesByType(type: EntityType): List<Entity> = database.runInTransaction<List<Entity>> {
        hydrate(dao.entitiesOfType(type.name)).sortedBy { it.id }
    }

    override fun searchEntities(keyword: String): List<Entity> = database.runInTransaction<List<Entity>> {
        hydrate(dao.allEntities().filter { it.label.contains(keyword, ignoreCase = true) }).sortedBy { it.id }
    }

    override fun addRelationship(relationship: Relationship) {
        database.runInTransaction {
            if (dao.entity(relationship.fromId) == null) throw UnknownEntityException(relationship.fromId)
            if (dao.entity(relationship.toId) == null) throw UnknownEntityException(relationship.toId)
            dao.upsertRelationship(relationship.toRow())
            dao.deleteRelationshipProperties(relationship.id)
            dao.insertRelationshipProperties(relationship.properties.map { (k, v) -> RelationshipPropertyRow(relationship.id, k, v) })
        }
    }

    override fun removeRelationship(id: String): Boolean = dao.deleteRelationship(id) > 0

    override fun relationshipsFrom(entityId: String): List<Relationship> = database.runInTransaction<List<Relationship>> {
        hydrateRelationships(dao.relationshipsFrom(entityId)).sortedBy { it.id }
    }

    override fun relationshipsTo(entityId: String): List<Relationship> = database.runInTransaction<List<Relationship>> {
        hydrateRelationships(dao.relationshipsTo(entityId)).sortedBy { it.id }
    }

    override fun neighbors(entityId: String, relationshipType: String?): List<Entity> = database.runInTransaction<List<Entity>> {
        neighborsOf(entityId, relationshipType)
    }

    override fun traverse(startId: String, maxDepth: Int, relationshipType: String?): List<Entity> {
        require(maxDepth >= 0) { "maxDepth must be >= 0, got $maxDepth" }
        return database.runInTransaction<List<Entity>> {
            val visited = mutableSetOf(startId)
            val result = mutableListOf<Entity>()
            var frontier = listOf(startId)
            var depth = 0
            while (frontier.isNotEmpty() && depth < maxDepth) {
                val next = mutableListOf<String>()
                for (id in frontier) {
                    for (neighbor in neighborsOf(id, relationshipType)) {
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

    private fun neighborsOf(entityId: String, relationshipType: String?): List<Entity> =
        hydrate(dao.neighborIds(entityId, relationshipType).distinct().chunked(CHUNK).flatMap(dao::entities)).sortedBy { it.id }

    private fun hydrate(rows: List<EntityRow>): List<Entity> {
        if (rows.isEmpty()) return emptyList()
        val props = rows.map { it.id }.chunked(CHUNK).flatMap(dao::entityProperties).groupBy { it.entityId }
        return rows.map { row ->
            Entity(
                id = row.id,
                type = EntityType.entries.firstOrNull { it.name == row.type } ?: EntityType.CONCEPT,
                label = row.label,
                properties = props[row.id].orEmpty().associate { it.key to it.value },
                createdAt = Instant.parse(row.createdAt),
                updatedAt = Instant.parse(row.updatedAt),
            )
        }
    }

    private fun hydrateRelationships(rows: List<RelationshipRow>): List<Relationship> {
        if (rows.isEmpty()) return emptyList()
        val props = rows.map { it.id }.chunked(CHUNK).flatMap(dao::relationshipProperties).groupBy { it.relationshipId }
        return rows.map { row ->
            Relationship(
                id = row.id,
                fromId = row.fromId,
                toId = row.toId,
                type = row.type,
                properties = props[row.id].orEmpty().associate { it.key to it.value },
                createdAt = Instant.parse(row.createdAt),
            )
        }
    }

    private fun Entity.toRow() = EntityRow(id, type.name, label, createdAt.toString(), updatedAt.toString())

    private fun Relationship.toRow() = RelationshipRow(id, fromId, toId, type, createdAt.toString())

    companion object {
        const val DATABASE_NAME = "knowledge.db"

        // Older Android SQLite caps bound variables at 999 per statement.
        private const val CHUNK = 500

        fun open(context: Context): RoomKnowledgeGraph =
            RoomKnowledgeGraph(Room.databaseBuilder(context, KnowledgeDatabase::class.java, DATABASE_NAME).build())
    }
}
