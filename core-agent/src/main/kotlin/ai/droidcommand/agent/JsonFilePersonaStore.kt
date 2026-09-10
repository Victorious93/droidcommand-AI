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
private data class VocabProfileDto(
    @SerialName("complexity_level") val complexityLevel: String,
    @SerialName("notable_vocabulary") val notableVocabulary: List<String> = emptyList(),
    @SerialName("jargon_domains") val jargonDomains: List<String> = emptyList(),
)

@Serializable
private data class StructureProfileDto(
    @SerialName("typical_sentence_length") val typicalSentenceLength: String,
    @SerialName("rhythm_description") val rhythmDescription: String,
    @SerialName("punctuation_habits") val punctuationHabits: List<String> = emptyList(),
)

@Serializable
private data class HumorProfileDto(
    val present: Boolean,
    val style: String? = null,
    val examples: List<String> = emptyList(),
)

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
    @SerialName("source_conversations") val sourceConversations: List<String>,
    @SerialName("style_characteristics") val styleCharacteristics: StyleProfileDto,
    @SerialName("context_contribution") val contextContribution: String,
    val version: String,
    val enabled: Boolean,
)

/**
 * A real, file-backed [PersonaStore]: each persona is one JSON file under
 * [directory], surviving a process restart — the same guarantee
 * [JsonFileKnowledgeStore] already provides for knowledge entries, applied
 * to CAP-005's persona storage instead.
 *
 * [Persona.id] must match [ID_PATTERN]; the resolved file path is then
 * re-checked to stay inside [directory] before any read/write/delete/
 * enable-toggle — the identical fail-closed, normalize-then-`startsWith`
 * pattern every other `JsonFile*Store` in this codebase uses.
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
        val bytes = json.encodeToString(PersonaDto.serializer(), persona.toDto()).toByteArray()
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
            throw IOException("Persona file for '$id' has an unknown enum value", e)
        }
    }

    override fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    override fun delete(id: String): Boolean = Files.deleteIfExists(fileFor(id))

    override fun setEnabled(id: String, enabled: Boolean): Boolean {
        val existing = load(id) ?: return false
        save(existing.copy(enabled = enabled))
        return true
    }

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

private fun Persona.toDto(): PersonaDto =
    PersonaDto(
        id = id,
        name = name,
        category = category.name,
        sourceConversations = sourceConversations,
        styleCharacteristics = styleCharacteristics.toDto(),
        contextContribution = contextContribution,
        version = version,
        enabled = enabled,
    )

private fun StyleProfile.toDto(): StyleProfileDto =
    StyleProfileDto(
        tone = tone,
        vocabulary = VocabProfileDto(vocabulary.complexityLevel, vocabulary.notableVocabulary, vocabulary.jargonDomains),
        sentenceStructure = StructureProfileDto(
            sentenceStructure.typicalSentenceLength,
            sentenceStructure.rhythmDescription,
            sentenceStructure.punctuationHabits,
        ),
        formality = formality.name,
        verbosity = verbosity.name,
        humor = HumorProfileDto(humor.present, humor.style, humor.examples),
        responseStructure = responseStructure,
        commonExpressions = commonExpressions,
    )

private fun PersonaDto.toDomain(): Persona =
    Persona(
        id = id,
        name = name,
        category = PersonaCategory.valueOf(category),
        sourceConversations = sourceConversations,
        styleCharacteristics = styleCharacteristics.toDomain(),
        contextContribution = contextContribution,
        version = version,
        enabled = enabled,
    )

private fun StyleProfileDto.toDomain(): StyleProfile =
    StyleProfile(
        tone = tone,
        vocabulary = VocabProfile(vocabulary.complexityLevel, vocabulary.notableVocabulary, vocabulary.jargonDomains),
        sentenceStructure = StructureProfile(
            sentenceStructure.typicalSentenceLength,
            sentenceStructure.rhythmDescription,
            sentenceStructure.punctuationHabits,
        ),
        formality = Formality.valueOf(formality),
        verbosity = Verbosity.valueOf(verbosity),
        humor = HumorProfile(humor.present, humor.style, humor.examples),
        responseStructure = responseStructure,
        commonExpressions = commonExpressions,
    )
