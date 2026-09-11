package ai.droidcommand.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

class InvalidEntityId(id: String) : IllegalArgumentException(
    "Entity id '$id' must match ${JsonFileKnowledgeGraph.ID_PATTERN.pattern} (no path separators or traversal)",
)

class InvalidRelationshipId(id: String) : IllegalArgumentException(
    "Relationship id '$id' must match ${JsonFileKnowledgeGraph.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class EntityDto(
    val id: String,
    val type: String,
    val label: String,
    val properties: Map<String, String> = emptyMap(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class RelationshipDto(
    val id: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val type: String,
    val properties: Map<String, String> = emptyMap(),
    @SerialName("created_at") val createdAt: String,
)

/**
 * A real, file-backed [KnowledgeGraph]: each [Entity] is one JSON file under
 * `directory/entities/`, each [Relationship] one JSON file under
 * `directory/relationships/` — surviving a process restart, the same
 * guarantee [JsonFileKnowledgeStore] already provides for flat
 * [KnowledgeEntry] items, applied to CAP-007's typed-entity/relationship
 * shape instead.
 *
 * Entity ids and relationship ids each independently follow
 * [JsonFileKnowledgeStore]'s exact convention: must match [ID_PATTERN], then
 * the resolved file path is re-checked to stay inside its subdirectory
 * before any read/write/delete — the identical fail-closed,
 * normalize-then-`startsWith` pattern.
 *
 * [entitiesByType]/[searchEntities]/[relationshipsFrom]/[relationshipsTo]/
 * [neighbors]/[traverse] are implemented in terms of [listEntityIds]/
 * [getEntity]/[listRelationships], so they genuinely re-read from disk on
 * every call rather than trusting any in-process cache — same discipline
 * [JsonFileKnowledgeStore.findByTag]/[JsonFileKnowledgeStore.search]
 * document for themselves. [removeEntity]'s cascade is therefore an honest
 * O(n) scan over every relationship file, stated here rather than hidden.
 */
class JsonFileKnowledgeGraph(private val directory: Path) : KnowledgeGraph {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val entitiesDir = directory.resolve("entities")
    private val relationshipsDir = directory.resolve("relationships")

    init {
        Files.createDirectories(entitiesDir)
        Files.createDirectories(relationshipsDir)
    }

    override fun addEntity(entity: Entity) {
        val file = entityFile(entity.id)
        val dto = EntityDto(
            id = entity.id,
            type = entity.type.name,
            label = entity.label,
            properties = entity.properties,
            createdAt = entity.createdAt.toString(),
            updatedAt = entity.updatedAt.toString(),
        )
        val bytes = json.encodeToString(EntityDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun getEntity(id: String): Entity? {
        val file = entityFile(id)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(EntityDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Entity file for '$id' is not valid JSON", e)
        }
        val type = try {
            EntityType.valueOf(dto.type)
        } catch (e: IllegalArgumentException) {
            throw IOException("Entity file for '$id' has an unrecognized type '${dto.type}'", e)
        }
        val (createdAt, updatedAt) = try {
            Instant.parse(dto.createdAt) to Instant.parse(dto.updatedAt)
        } catch (e: java.time.format.DateTimeParseException) {
            throw IOException("Entity file for '$id' has an unparseable timestamp", e)
        }
        return Entity(dto.id, type, dto.label, dto.properties, createdAt, updatedAt)
    }

    override fun removeEntity(id: String): Boolean {
        val existed = Files.deleteIfExists(entityFile(id))
        if (existed) {
            listRelationships()
                .filter { it.fromId == id || it.toId == id }
                .forEach { Files.deleteIfExists(relationshipFile(it.id)) }
        }
        return existed
    }

    override fun entitiesByType(type: EntityType): List<Entity> =
        listEntityIds().mapNotNull(::getEntity).filter { it.type == type }.sortedBy { it.id }

    override fun searchEntities(keyword: String): List<Entity> =
        listEntityIds().mapNotNull(::getEntity).filter { it.label.contains(keyword, ignoreCase = true) }.sortedBy { it.id }

    override fun addRelationship(relationship: Relationship) {
        if (getEntity(relationship.fromId) == null) throw UnknownEntityException(relationship.fromId)
        if (getEntity(relationship.toId) == null) throw UnknownEntityException(relationship.toId)
        val file = relationshipFile(relationship.id)
        val dto = RelationshipDto(
            id = relationship.id,
            fromId = relationship.fromId,
            toId = relationship.toId,
            type = relationship.type,
            properties = relationship.properties,
            createdAt = relationship.createdAt.toString(),
        )
        val bytes = json.encodeToString(RelationshipDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun removeRelationship(id: String): Boolean = Files.deleteIfExists(relationshipFile(id))

    override fun relationshipsFrom(entityId: String): List<Relationship> =
        listRelationships().filter { it.fromId == entityId }.sortedBy { it.id }

    override fun relationshipsTo(entityId: String): List<Relationship> =
        listRelationships().filter { it.toId == entityId }.sortedBy { it.id }

    override fun neighbors(entityId: String, relationshipType: String?): List<Entity> =
        neighborsOf(entityId, relationshipType)

    private fun neighborsOf(entityId: String, relationshipType: String?): List<Entity> {
        val neighborIds = listRelationships()
            .asSequence()
            .filter { relationshipType == null || it.type == relationshipType }
            .filter { it.fromId == entityId || it.toId == entityId }
            .map { if (it.fromId == entityId) it.toId else it.fromId }
            .toSet()
        return neighborIds.mapNotNull(::getEntity).sortedBy { it.id }
    }

    override fun traverse(startId: String, maxDepth: Int, relationshipType: String?): List<Entity> {
        require(maxDepth >= 0) { "maxDepth must be >= 0, got $maxDepth" }
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
        return result
    }

    private fun listEntityIds(): List<String> {
        if (!Files.isDirectory(entitiesDir)) return emptyList()
        Files.newDirectoryStream(entitiesDir, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    private fun listRelationships(): List<Relationship> {
        if (!Files.isDirectory(relationshipsDir)) return emptyList()
        val ids = Files.newDirectoryStream(relationshipsDir, "*$EXTENSION").use { stream ->
            stream.map { it.fileName.toString().removeSuffix(EXTENSION) }
        }
        return ids.mapNotNull { id ->
            val file = relationshipFile(id)
            val dto = try {
                json.decodeFromString(RelationshipDto.serializer(), Files.readString(file))
            } catch (e: SerializationException) {
                throw IOException("Relationship file for '$id' is not valid JSON", e)
            }
            val createdAt = try {
                Instant.parse(dto.createdAt)
            } catch (e: java.time.format.DateTimeParseException) {
                throw IOException("Relationship file for '$id' has an unparseable timestamp", e)
            }
            Relationship(dto.id, dto.fromId, dto.toId, dto.type, dto.properties, createdAt)
        }
    }

    private fun entityFile(id: String): Path {
        if (!ID_PATTERN.matches(id)) throw InvalidEntityId(id)
        val normalizedRoot = entitiesDir.normalize()
        val candidate = normalizedRoot.resolve("$id$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidEntityId(id)
        return candidate
    }

    private fun relationshipFile(id: String): Path {
        if (!ID_PATTERN.matches(id)) throw InvalidRelationshipId(id)
        val normalizedRoot = relationshipsDir.normalize()
        val candidate = normalizedRoot.resolve("$id$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidRelationshipId(id)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".json"
    }
}
