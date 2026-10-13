package ai.droidcommand.coding

import java.nio.file.Files
import java.nio.file.Path

/**
 * Persists sessions as [CodingWire] records, six lines each, back to back.
 * A trailing incomplete record is skipped. Field newlines are already escaped by the wire form.
 */
class FileCodingLog(private val path: Path) {
    fun load(): List<CodingSession> {
        if (!Files.exists(path)) return emptyList()
        return Files.readAllLines(path).chunked(6).mapNotNull { chunk ->
            if (chunk.size != 6) return@mapNotNull null
            runCatching { CodingWire.decode(chunk.joinToString("\n")) }.getOrNull()
        }
    }

    fun save(sessions: List<CodingSession>) {
        path.parent?.let { Files.createDirectories(it) }
        val text = sessions.joinToString("\n") { CodingWire.encode(it) }
        Files.writeString(path, if (text.isEmpty()) "" else text + "\n")
    }
}
