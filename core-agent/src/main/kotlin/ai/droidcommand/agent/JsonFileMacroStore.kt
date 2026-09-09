package ai.droidcommand.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InvalidMacroName(name: String) : IllegalArgumentException(
    "Macro name '$name' must match ${JsonFileMacroStore.NAME_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class MacroStepDto(@SerialName("tool_name") val toolName: String, val input: Map<String, String>)

@Serializable
private data class MacroDto(val name: String, val steps: List<MacroStepDto>)

/**
 * A real, file-backed [MacroStore]: each macro is one JSON file under
 * [directory], surviving a process restart — the persistence
 * [InMemoryMacroStore] deliberately doesn't provide.
 *
 * [Macro.name] must match [NAME_PATTERN]; this both keeps filenames simple
 * and — combined with the normalize-then-`startsWith` check in [fileFor],
 * the same fail-closed pattern `core-build.WorkspacePathValidator` uses for
 * workspace paths — makes a macro name unable to ever resolve outside
 * [directory], so no name can read, overwrite, or delete a file elsewhere
 * on disk.
 */
class JsonFileMacroStore(private val directory: Path) : MacroStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        Files.createDirectories(directory)
    }

    override fun save(macro: Macro) {
        val file = fileFor(macro.name)
        val dto = MacroDto(macro.name, macro.steps.map { MacroStepDto(it.toolName, it.input) })
        val bytes = json.encodeToString(MacroDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun load(name: String): Macro? {
        val file = fileFor(name)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(MacroDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Macro file for '$name' is not valid JSON", e)
        }
        return Macro(dto.name, dto.steps.map { MacroStep(it.toolName, it.input) })
    }

    override fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    override fun delete(name: String): Boolean = Files.deleteIfExists(fileFor(name))

    private fun fileFor(name: String): Path {
        if (!NAME_PATTERN.matches(name)) throw InvalidMacroName(name)
        val normalizedRoot = directory.normalize()
        val candidate = normalizedRoot.resolve("$name$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidMacroName(name)
        return candidate
    }

    companion object {
        val NAME_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".macro.json"
    }
}
