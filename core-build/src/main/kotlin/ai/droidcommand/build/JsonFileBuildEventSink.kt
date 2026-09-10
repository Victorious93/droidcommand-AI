package ai.droidcommand.build

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeParseException

class InvalidBuildId(buildId: String) : IllegalArgumentException(
    "Build id '$buildId' must match ${JsonFileBuildEventSink.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class BuildEventDto(
    val type: String,
    @SerialName("build_id") val buildId: String,
    val timestamp: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * A real, file-backed [BuildEventSink]: every [BuildEvent] for a given
 * build is appended as one JSON-Lines record to its own file under
 * [directory], surviving a process restart — closing ROADMAP-078's gap.
 * [BuildEventSink]'s own doc comment already names exactly this as the
 * intended extension point ("a future ... build-history store consumes it
 * by implementing this interface, not by this module growing a
 * database"); this is that implementation, extending the same append-only
 * pattern `core-security.JsonFileAuditLog` uses (an audit/log trail exists
 * to prove something happened even after the process is gone, so there is
 * deliberately no delete/rewrite path here either), keyed per build id the
 * way `core-agent.JsonFileKnowledgeStore` keys per entry id.
 *
 * [BuildEvent.buildId] must match [ID_PATTERN]; the resolved file path is
 * then re-checked to stay inside [directory] before any read/write — the
 * identical fail-closed, normalize-then-`startsWith` pattern this
 * module's own [WorkspacePathValidator] already established, and every
 * `JsonFile*` store elsewhere in this codebase mirrors.
 */
class JsonFileBuildEventSink(private val directory: Path) : BuildEventSink {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    init {
        Files.createDirectories(directory)
    }

    override fun emit(event: BuildEvent) {
        synchronized(lock) {
            val file = fileFor(event.buildId)
            val dto = BuildEventDto(event.type.name, event.buildId, event.timestamp.toString(), event.message, event.metadata)
            val line = json.encodeToString(BuildEventDto.serializer(), dto) + "\n"
            Files.write(file, line.toByteArray(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }

    /** Every build id with at least one recorded event, sorted. */
    fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    /** Every event recorded for [buildId], oldest first; empty if none were ever recorded. */
    fun load(buildId: String): List<BuildEvent> {
        val file = fileFor(buildId)
        if (!Files.isRegularFile(file)) return emptyList()
        return Files.readAllLines(file).filter { it.isNotBlank() }.map(::parseLine)
    }

    private fun parseLine(line: String): BuildEvent {
        val dto = try {
            json.decodeFromString(BuildEventDto.serializer(), line)
        } catch (e: SerializationException) {
            throw IOException("Build log line is not valid JSON: $line", e)
        }
        val timestamp = try {
            Instant.parse(dto.timestamp)
        } catch (e: DateTimeParseException) {
            throw IOException("Build log entry has an unparseable timestamp: ${dto.timestamp}", e)
        }
        return BuildEvent(BuildEventType.valueOf(dto.type), dto.buildId, timestamp, dto.message, dto.metadata)
    }

    private fun fileFor(buildId: String): Path {
        if (!ID_PATTERN.matches(buildId)) throw InvalidBuildId(buildId)
        val normalizedRoot = directory.normalize()
        val candidate = normalizedRoot.resolve("$buildId$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidBuildId(buildId)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".build.jsonl"
    }
}
