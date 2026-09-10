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

class InvalidKnowledgeEntryId(id: String) : IllegalArgumentException(
    "Knowledge entry id '$id' must match ${JsonFileKnowledgeStore.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class KnowledgeEntryDto(
    val id: String,
    val content: String,
    val source: String,
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * A real, file-backed [KnowledgeStore]: each entry is one JSON file under
 * [directory], surviving a process restart — the same guarantee
 * [JsonFileMacroStore]/[JsonFileConversationStore] already provide for
 * macros and conversations, applied to ROADMAP-126's long-term-knowledge
 * gap instead.
 *
 * [KnowledgeEntry.id] must match [ID_PATTERN]; the resolved file path is
 * then re-checked to stay inside [directory] before any read/write/delete
 * — the identical fail-closed, normalize-then-`startsWith` pattern
 * [JsonFileMacroStore] and `core-build.WorkspacePathValidator` both use.
 *
 * [findByTag]/[search] are implemented in terms of [list]/[load], so they
 * genuinely re-read from disk on every call rather than trusting any
 * in-process cache.
 */
class JsonFileKnowledgeStore(private val directory: Path) : KnowledgeStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        Files.createDirectories(directory)
    }

    override fun save(entry: KnowledgeEntry) {
        val file = fileFor(entry.id)
        val dto = KnowledgeEntryDto(
            id = entry.id,
            content = entry.content,
            source = entry.source,
            tags = entry.tags.toList(),
            createdAt = entry.createdAt.toString(),
            updatedAt = entry.updatedAt.toString(),
        )
        val bytes = json.encodeToString(KnowledgeEntryDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun load(id: String): KnowledgeEntry? {
        val file = fileFor(id)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(KnowledgeEntryDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Knowledge entry file for '$id' is not valid JSON", e)
        }
        val (createdAt, updatedAt) = try {
            Instant.parse(dto.createdAt) to Instant.parse(dto.updatedAt)
        } catch (e: java.time.format.DateTimeParseException) {
            throw IOException("Knowledge entry file for '$id' has an unparseable timestamp", e)
        }
        return KnowledgeEntry(dto.id, dto.content, dto.source, dto.tags.toSet(), createdAt, updatedAt)
    }

    override fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    override fun delete(id: String): Boolean = Files.deleteIfExists(fileFor(id))

    override fun findByTag(tag: String): List<KnowledgeEntry> =
        list().mapNotNull { load(it) }.filter { tag in it.tags }.sortedBy { it.id }

    override fun search(keyword: String): List<KnowledgeEntry> =
        list().mapNotNull { load(it) }.filter { it.content.contains(keyword, ignoreCase = true) }.sortedBy { it.id }

    private fun fileFor(id: String): Path {
        if (!ID_PATTERN.matches(id)) throw InvalidKnowledgeEntryId(id)
        val normalizedRoot = directory.normalize()
        val candidate = normalizedRoot.resolve("$id$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidKnowledgeEntryId(id)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".knowledge.json"
    }
}
