package ai.droidcommand.notes.studio

import ai.droidcommand.notes.NoteVault

data class SavedPrompt(val category: String, val title: String, val body: String, val path: String)

/** Prompts saved as plain markdown notes under `Prompts/<category>/<title>.md`, so they sync with the rest of the vault. */
class PromptVault(private val vault: NoteVault) {
    fun categories(): List<String> = all().map { it.category }.distinct().sorted()

    fun all(): List<SavedPrompt> = vault.listAllMarkdown()
        .filter { it.startsWith("$ROOT/") }
        .mapNotNull { path ->
            val parts = path.removePrefix("$ROOT/").split('/')
            val category = if (parts.size > 1) parts.dropLast(1).joinToString("/") else GENERAL
            runCatching { SavedPrompt(category, parts.last().removeSuffix(".md"), vault.read(path), path) }.getOrNull()
        }
        .sortedWith(compareBy({ it.category.lowercase() }, { it.title.lowercase() }))

    fun search(query: String, category: String? = null): List<SavedPrompt> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return all().filter { category == null || it.category == category }.filter { p ->
            val hay = (p.title + "\n" + p.body + "\n" + p.category).lowercase()
            words.all { it in hay }
        }
    }

    fun save(category: String, title: String, body: String): SavedPrompt {
        val cat = clean(category).ifEmpty { GENERAL }
        val name = clean(title)
        require(name.isNotEmpty()) { "A prompt needs a title" }
        val path = "$ROOT/$cat/$name.md"
        vault.write(path, body)
        return SavedPrompt(cat, name, body, path)
    }

    fun delete(prompt: SavedPrompt) = vault.delete(prompt.path)

    companion object {
        const val ROOT = "Prompts"
        const val GENERAL = "General"

        /** Keeps a name safe as one path part: no slashes, no leading dots, nothing outside the vault. */
        fun clean(name: String): String = name.replace(Regex("""[/\\:*?"<>|\n\r\t]"""), " ").trim().trimStart('.').trim().take(80)
    }
}
