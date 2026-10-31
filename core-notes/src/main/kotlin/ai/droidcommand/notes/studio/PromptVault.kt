package ai.droidcommand.notes.studio

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID

data class SavedPrompt(val id: String, val title: String, val category: String, val body: String)

/**
 * Saved prompts grouped by category. Stored as one text file; each record is
 * four base64 fields on one line, so bodies may hold any text.
 */
class PromptVault(private val file: Path, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    fun all(): List<SavedPrompt> {
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).mapNotNull(::decode)
    }

    fun categories(): List<String> = all().map { it.category }.distinct().sorted()

    /** Case-insensitive match on title, category, or body. Blank query + null category returns everything. */
    fun search(query: String, category: String? = null): List<SavedPrompt> {
        val q = query.trim().lowercase()
        return all().filter { p ->
            (category == null || p.category == category) &&
                (q.isEmpty() || p.title.lowercase().contains(q) || p.body.lowercase().contains(q) || p.category.lowercase().contains(q))
        }.sortedWith(compareBy({ it.category.lowercase() }, { it.title.lowercase() }))
    }

    fun save(title: String, category: String, body: String, id: String? = null): SavedPrompt {
        require(title.isNotBlank()) { "Give the prompt a title." }
        require(body.isNotBlank()) { "The prompt is empty." }
        val prompt = SavedPrompt(id ?: newId(), title.trim(), category.trim().ifEmpty { "General" }, body)
        val rest = all().filterNot { it.id == prompt.id }
        write(rest + prompt)
        return prompt
    }

    fun delete(id: String): Boolean {
        val before = all()
        val after = before.filterNot { it.id == id }
        if (after.size == before.size) return false
        write(after)
        return true
    }

    private fun write(prompts: List<SavedPrompt>) {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, prompts.map(::encode))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun encode(p: SavedPrompt) = listOf(p.id, p.title, p.category, p.body).joinToString("\t") { b64(it) }

    private fun decode(line: String): SavedPrompt? {
        val parts = line.split('\t')
        if (parts.size != 4) return null
        return runCatching { SavedPrompt(unb64(parts[0]), unb64(parts[1]), unb64(parts[2]), unb64(parts[3])) }.getOrNull()
    }

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
    private fun unb64(s: String) = String(Base64.getDecoder().decode(s), Charsets.UTF_8)
}
