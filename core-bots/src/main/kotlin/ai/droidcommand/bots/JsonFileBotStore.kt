package ai.droidcommand.bots

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InvalidBotId(id: String) : IllegalArgumentException(
    "Bot id '$id' must match ${JsonFileBotStore.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class BotRecipeDto(
    val id: String,
    val name: String,
    @SerialName("persona_id") val personaId: String,
    val instructions: String = "",
    @SerialName("provider_type") val providerType: String,
    @SerialName("allowed_tools") val allowedTools: List<String> = emptyList(),
    @SerialName("memory_folder") val memoryFolder: String,
    val enabled: Boolean = true,
)

/**
 * A real, file-backed [BotStore]: each bot recipe is one JSON file under
 * [directory], surviving a process restart — the same guarantee
 * `ai.droidcommand.agent.JsonFilePersonaStore`/`JsonFileKnowledgeStore`/
 * `JsonFileMacroStore` already provide for their own domains, applied here
 * for Bot Maker (`docs/BRAIN_JARVIS_BOTMAKER_PLAN.md` §3, Phase B1).
 *
 * [BotRecipe.id] must match [ID_PATTERN]; the resolved file path is then
 * re-checked to stay inside [directory] before any read/write/delete —
 * the identical fail-closed, normalize-then-`startsWith` pattern those
 * stores use.
 */
class JsonFileBotStore(private val directory: Path) : BotStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        Files.createDirectories(directory)
    }

    override fun save(recipe: BotRecipe) {
        val file = fileFor(recipe.id)
        val dto = recipe.toDto()
        val bytes = json.encodeToString(BotRecipeDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun load(id: String): BotRecipe? {
        val file = fileFor(id)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(BotRecipeDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Bot recipe file for '$id' is not valid JSON", e)
        }
        return dto.toDomain()
    }

    override fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    override fun delete(id: String): Boolean = Files.deleteIfExists(fileFor(id))

    private fun fileFor(id: String): Path {
        if (!ID_PATTERN.matches(id)) throw InvalidBotId(id)
        val normalizedRoot = directory.normalize()
        val candidate = normalizedRoot.resolve("$id$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidBotId(id)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".bot.json"
    }
}

private fun BotRecipe.toDto() = BotRecipeDto(
    id = id,
    name = name,
    personaId = personaId,
    instructions = instructions,
    providerType = providerType,
    allowedTools = allowedTools.toList(),
    memoryFolder = memoryFolder,
    enabled = enabled,
)

private fun BotRecipeDto.toDomain() = BotRecipe(
    id = id,
    name = name,
    personaId = personaId,
    instructions = instructions,
    providerType = providerType,
    allowedTools = allowedTools.toSet(),
    memoryFolder = memoryFolder,
    enabled = enabled,
)
