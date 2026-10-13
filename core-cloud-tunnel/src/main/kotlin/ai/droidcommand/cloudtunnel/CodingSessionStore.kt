package ai.droidcommand.cloudtunnel

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

interface CodingSessionStore {
    fun list(): List<CodingSession>

    fun get(id: String): CodingSession?

    fun save(session: CodingSession)
}

class MemoryCodingSessionStore : CodingSessionStore {
    private val sessions = linkedMapOf<String, CodingSession>()

    override fun list(): List<CodingSession> = sessions.values.sortedByDescending { it.updatedAtEpochMs }

    override fun get(id: String): CodingSession? = sessions[id]

    override fun save(session: CodingSession) {
        sessions[session.id] = session
    }
}

/**
 * One JSON file of sessions. Writes go to a temporary file in the same directory, then move into
 * place, so a crash mid-write does not leave a half-written list as the only copy.
 */
class FileCodingSessionStore(
    private val file: Path,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) : CodingSessionStore {
    private val lock = Any()

    override fun list(): List<CodingSession> = synchronized(lock) { read().sortedByDescending { it.updatedAtEpochMs } }

    override fun get(id: String): CodingSession? = synchronized(lock) { read().firstOrNull { it.id == id } }

    override fun save(session: CodingSession) {
        synchronized(lock) {
            val next = read().filterNot { it.id == session.id } + session
            val capped = next.sortedByDescending { it.updatedAtEpochMs }.take(MAX_SESSIONS)
            write(capped)
        }
    }

    private fun read(): List<CodingSession> {
        if (!Files.isRegularFile(file)) return emptyList()
        val text = Files.readString(file)
        if (text.isBlank()) return emptyList()
        return json.decodeFromString(ListSerializer(CodingSession.serializer()), text)
    }

    private fun write(sessions: List<CodingSession>) {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, "sessions-", ".json.tmp")
        try {
            Files.writeString(temp, json.encodeToString(ListSerializer(CodingSession.serializer()), sessions))
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }

    companion object {
        const val MAX_SESSIONS = 50
    }
}
