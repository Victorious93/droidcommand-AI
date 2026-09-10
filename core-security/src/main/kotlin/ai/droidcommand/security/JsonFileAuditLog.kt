package ai.droidcommand.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

@Serializable
private data class AuditEventDto(
    val type: String,
    val subject: String,
    val detail: String,
    val timestamp: String,
)

/**
 * A real, file-backed [AuditLog]: every [AuditEvent] is appended as one
 * JSON-Lines record to [file], surviving a process restart — the same
 * persistence guarantee `JsonFileMacroStore`/`JsonFileConversationStore`/
 * `JsonFileKnowledgeStore`/[JsonFileGrantStore] already provide elsewhere,
 * applied to ROADMAP-049's audit-trail gap now that its prerequisite (a
 * real persistence pattern) exists.
 *
 * An audit trail's whole purpose is proving a privileged action happened
 * even after the process that ran it is gone, so — unlike the keyed stores
 * above — this is deliberately append-only with no `delete`/rewrite path:
 * [record] only ever appends a line, never truncates or rewrites the file.
 *
 * [record] fails closed at [capacity], counting the file's existing lines
 * fresh on every call rather than trusting an in-process counter — the
 * same "genuinely re-read from disk, never just cached" discipline
 * [JsonFileKnowledgeStore] already documents, and the only way capacity
 * stays correct if more than one [JsonFileAuditLog] instance (or process)
 * ever appends to the same [file].
 */
class JsonFileAuditLog(private val file: Path, private val capacity: Int = 10_000) : AuditLog {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    init {
        file.parent?.let { Files.createDirectories(it) }
        if (!Files.exists(file)) {
            Files.write(file, ByteArray(0), StandardOpenOption.CREATE)
        }
    }

    override fun record(event: AuditEvent): Boolean = synchronized(lock) {
        if (lineCount() >= capacity) return false
        val dto = AuditEventDto(event.type.name, event.subject, event.detail, event.timestamp.toString())
        val line = json.encodeToString(AuditEventDto.serializer(), dto) + "\n"
        Files.write(file, line.toByteArray(), StandardOpenOption.APPEND)
        true
    }

    /** Re-reads and parses every recorded event from [file], oldest first. */
    fun all(): List<AuditEvent> = Files.readAllLines(file).filter { it.isNotBlank() }.map { line ->
        val dto = try {
            json.decodeFromString(AuditEventDto.serializer(), line)
        } catch (e: SerializationException) {
            throw IOException("Audit log line is not valid JSON: $line", e)
        }
        val timestamp = try {
            Instant.parse(dto.timestamp)
        } catch (e: java.time.format.DateTimeParseException) {
            throw IOException("Audit log entry has an unparseable timestamp: ${dto.timestamp}", e)
        }
        AuditEvent(AuditEventType.valueOf(dto.type), dto.subject, dto.detail, timestamp)
    }

    private fun lineCount(): Int = Files.newBufferedReader(file).use { reader -> reader.lines().filter { it.isNotBlank() }.count().toInt() }
}
