package ai.droidcommand.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InvalidPersonaId(id: String) : IllegalArgumentException(
    "Persona id '$id' must match ${JsonFilePersonaStore.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class VocabProfileDto(val complexity: String, @SerialName("notable_words") val notableWords: List<String> = emptyList())

@Serializable
private data class StructureProfileDto(
    @SerialName("typical_sentence_length") val typicalSentenceLength: String,
    @SerialName("punctuation_habits") val punctuationHabits: String,
)

@Serializable
private data class HumorProfileDto(val present: Boolean, val style: String? = null)

@Serializable
private data class StyleProfileDto(
    val tone: String,
    val vocabulary: VocabProfileDto,
    @SerialName("sentence_structure") val sentenceStructure: StructureProfileDto,
    val formality: String,
    val verbosity: String,
    val humor: HumorProfileDto,
    @SerialName("response_structure") val responseStructure: String,
    @SerialName("common_expressions") val commonExpressions: List<String> = emptyList(),
)

@Serializable
private data class PersonaDto(
    val id: String,
    val name: String,
    val category: String,
    @SerialName("source_conversations") val sourceConversations: List<String> = emptyList(),
    val style: StyleProfileDto,
    @SerialName("context_contribution") val contextContribution: String,
    val version: String,
    val enabled: Boolean,
)

/**
 * A real, file-backed [PersonaStore]: each persona is one JSON file under
 * [directory], surviving a process restart — the same guarantee
 * [JsonFileKnowledgeStore]/[JsonFileMacroStore]/[JsonFileConversationStore]
 * already provide for their own domains, applied here to close the
 * `JsonFilePersonaStore` gap the CAP-005 addendum named.
 *
 * [Persona.id] must match [ID_PATTERN]; the resolved file path is then
 * re-checked to stay inside [directory] before any read/write/delete — the
 * identical fail-closed, normalize-then-`startsWith` pattern
 * [JsonFileKnowledgeStore]/[JsonFileMacroStore] both use.
 *
 * Kept deliberately decoupled from `kotlinx.serialization`: [Persona] and
 * its nested types ([StyleProfile], [VocabProfile], [StructureProfile],
 * [HumorProfile], [PersonaCategory], [Formality], [Verbosity]) carry no
 * serialization annotations of their own, matching [JsonFileMacroStore]'s
 * own precedent of a private DTO tree even where (unlike
 * [JsonFileKnowledgeStore]'s `Instant` fields) nothing here is JSON-hostile
 * — the domain model stays a plain Kotlin type regardless of which
 * serialization library (or none) a store implementation happens to use.
 * Every enum is stored by name and re-parsed via `valueOf` on load; an
 * unrecognized value (a hand-edited or corrupted file) throws [IOException]
 * naming the persona id and the bad value, the same honesty
 * [JsonFileKnowledgeStore]'s own unparseable-timestamp handling already
 * applies to its own JSON-hostile field.
 */
class JsonFilePersonaStore(private val directory: Path) : PersonaStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        Files.createDirectories(directory)
    }

    override fun save(persona: Persona) {
        val file = fileFor(persona.id)
        val dto = persona.toDto()
        val bytes = json.encodeToString(PersonaDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun load(id: String): Persona? {
        val file = fileFor(id)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(PersonaDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Persona file for '$id' is not valid JSON", e)
        }
        return try {
            dto.toDomain()
        } catch (e: IllegalArgumentException) {
            throw IOException("Persona file for '$id' has an unparseable enum value: ${e.message}", e)
        }
    }

    override fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    override fun delete(id: String): Boolean = Files.deleteIfExists(fileFor(id))

    private fun fileFor(id: String): Path {
        if (!ID_PATTERN.matches(id)) throw InvalidPersonaId(id)
        val normalizedRoot = directory.normalize()
        val candidate = normalizedRoot.resolve("$id$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidPersonaId(id)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".persona.json"
    }
}

private fun Persona.toDto() = PersonaDto(
    id = id,
    name = name,
    category = category.name,
    sourceConversations = sourceConversations,
    style = styleCharacteristics.toDto(),
    contextContribution = contextContribution,
    version = version,
    enabled = enabled,
)

private fun StyleProfile.toDto() = StyleProfileDto(
    tone = tone,
    vocabulary = VocabProfileDto(vocabulary.complexity, vocabulary.notableWords),
    sentenceStructure = StructureProfileDto(sentenceStructure.typicalSentenceLength, sentenceStructure.punctuationHabits),
    formality = formality.name,
    verbosity = verbosity.name,
    humor = HumorProfileDto(humor.present, humor.style),
    responseStructure = responseStructure,
    commonExpressions = commonExpressions,
)

private fun PersonaDto.toDomain() = Persona(
    id = id,
    name = name,
    category = PersonaCategory.valueOf(category),
    sourceConversations = sourceConversations,
    styleCharacteristics = style.toDomain(),
    contextContribution = contextContribution,
    version = version,
    enabled = enabled,
)

private fun StyleProfileDto.toDomain() = StyleProfile(
    tone = tone,
    vocabulary = VocabProfile(vocabulary.complexity, vocabulary.notableWords),
    sentenceStructure = StructureProfile(sentenceStructure.typicalSentenceLength, sentenceStructure.punctuationHabits),
    formality = Formality.valueOf(formality),
    verbosity = Verbosity.valueOf(verbosity),
    humor = HumorProfile(humor.present, humor.style),
    responseStructure = responseStructure,
    commonExpressions = commonExpressions,
)
