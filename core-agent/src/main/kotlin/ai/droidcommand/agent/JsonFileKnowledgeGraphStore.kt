package ai.droidcommand.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InvalidGraphNodeId(id: String) : IllegalArgumentException(
    "Graph node id '$id' must match ${JsonFileKnowledgeGraphStore.ID_PATTERN.pattern} (no path separators or traversal)",
)

class InvalidGraphEdgeId(id: String) : IllegalArgumentException(
    "Graph edge id '$id' must match ${JsonFileKnowledgeGraphStore.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class GraphNodeDto(
    val id: String,
    val type: String,
    val label: String,
    val properties: Map<String, String> = emptyMap(),
)

@Serializable
private data class GraphEdgeDto(
    val id: String,
    @SerialName("from_id") val fromId: String,
    @SerialName("to_id") val toId: String,
    val relationship: String,
    val properties: Map<String, String> = emptyMap(),
)

/**
 * A real, file-backed [KnowledgeGraphStore]: each node and edge is one
 * JSON file, surviving a process restart — the same guarantee
 * [JsonFileKnowledgeStore] already provides for flat knowledge entries.
 * Nodes and edges live under separate `nodes/`/`edges/` subdirectories of
 * [directory], each its own id namespace with the identical fail-closed,
 * normalize-then-`startsWith` path-escape defense every other
 * `JsonFile*Store` in this codebase uses.
 *
 * [edges] is an honest O(n) scan over every edge file — it genuinely
 * re-reads from disk on every call, never cached, the same "no index,
 * real cost stated plainly" discipline [JsonFileKnowledgeStore.findByTag]/
 * [JsonFileKnowledgeStore.search] already document.
 */
class JsonFileKnowledgeGraphStore(private val directory: Path) : KnowledgeGraphStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val nodesDir = directory.resolve("nodes")
    private val edgesDir = directory.resolve("edges")

    init {
        Files.createDirectories(nodesDir)
        Files.createDirectories(edgesDir)
    }

    override fun saveNode(node: GraphNode) {
        val file = nodeFile(node.id)
        val dto = GraphNodeDto(node.id, node.type.name, node.label, node.properties)
        val bytes = json.encodeToString(GraphNodeDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun loadNode(id: String): GraphNode? {
        val file = nodeFile(id)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(GraphNodeDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Graph node file for '$id' is not valid JSON", e)
        }
        val type = try {
            EntityType.valueOf(dto.type)
        } catch (e: IllegalArgumentException) {
            throw IOException("Graph node file for '$id' has an unknown type '${dto.type}'", e)
        }
        return GraphNode(dto.id, type, dto.label, dto.properties)
    }

    override fun deleteNode(id: String, cascade: Boolean): Boolean {
        val existed = Files.deleteIfExists(nodeFile(id))
        if (existed && cascade) {
            listEdgeIds()
                .mapNotNull(::loadEdge)
                .filter { it.fromId == id || it.toId == id }
                .forEach { deleteEdge(it.id) }
        }
        return existed
    }

    override fun listNodes(type: EntityType?): List<String> {
        if (!Files.isDirectory(nodesDir)) return emptyList()
        val ids = Files.newDirectoryStream(nodesDir, "*$EXTENSION").use { stream -> stream.map { it.fileName.toString().removeSuffix(EXTENSION) } }
        val filtered = if (type == null) ids else ids.filter { loadNode(it)?.type == type }
        return filtered.sorted()
    }

    override fun saveEdge(edge: GraphEdge) {
        val file = edgeFile(edge.id)
        val dto = GraphEdgeDto(edge.id, edge.fromId, edge.toId, edge.relationship, edge.properties)
        val bytes = json.encodeToString(GraphEdgeDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun loadEdge(id: String): GraphEdge? {
        val file = edgeFile(id)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(GraphEdgeDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Graph edge file for '$id' is not valid JSON", e)
        }
        return GraphEdge(dto.id, dto.fromId, dto.toId, dto.relationship, dto.properties)
    }

    override fun deleteEdge(id: String): Boolean = Files.deleteIfExists(edgeFile(id))

    override fun edges(nodeId: String, relationship: String?, direction: EdgeDirection): List<GraphEdge> =
        listEdgeIds().mapNotNull(::loadEdge)
            .filter { edge ->
                matchesDirection(edge, nodeId, direction) && (relationship == null || edge.relationship == relationship)
            }
            .sortedBy { it.id }

    private fun matchesDirection(edge: GraphEdge, nodeId: String, direction: EdgeDirection): Boolean = when (direction) {
        EdgeDirection.OUTGOING -> edge.fromId == nodeId
        EdgeDirection.INCOMING -> edge.toId == nodeId
        EdgeDirection.BOTH -> edge.fromId == nodeId || edge.toId == nodeId
    }

    private fun listEdgeIds(): List<String> {
        if (!Files.isDirectory(edgesDir)) return emptyList()
        Files.newDirectoryStream(edgesDir, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }
        }
    }

    private fun nodeFile(id: String): Path = resolveWithin(nodesDir, id, ::InvalidGraphNodeId)

    private fun edgeFile(id: String): Path = resolveWithin(edgesDir, id, ::InvalidGraphEdgeId)

    private fun resolveWithin(root: Path, id: String, onInvalid: (String) -> IllegalArgumentException): Path {
        if (!ID_PATTERN.matches(id)) throw onInvalid(id)
        val normalizedRoot = root.normalize()
        val candidate = normalizedRoot.resolve("$id$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw onInvalid(id)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".json"
    }
}
