package ai.droidcommand.notes

data class NoteIndexSnapshot(
    val notes: List<NoteDocument>,
    val byPath: Map<String, NoteDocument>,
    val backlinks: Map<String, List<String>>,
) {
    fun note(relativePath: String): NoteDocument? = byPath[relativePath]

    fun backlinksTo(relativePath: String): List<NoteDocument> {
        val sources = backlinks[relativePath].orEmpty()
        return sources.mapNotNull { byPath[it] }
    }

    fun resolveLink(target: String): NoteDocument? {
        val cleaned = target.trim().trimStart('/').removeSuffix(".md")
        if (cleaned.isEmpty()) return null
        byPath["$cleaned.md"]?.let { return it }
        val lower = cleaned.lowercase()
        val slash = lower.contains('/')
        return notes.firstOrNull { note ->
            note.relativePath.removeSuffix(".md").lowercase() == lower ||
                (!slash && note.stem.lowercase() == lower) ||
                (!slash && note.title.lowercase() == lower)
        }
    }
}

class NoteIndex(private val vault: NoteVault) {
    fun refresh(): NoteIndexSnapshot {
        val notes = vault.listAllMarkdown().map { path ->
            NoteParser.parse(path, vault.read(path))
        }
        val byPath = notes.associateBy { it.relativePath }
        val snapshot = NoteIndexSnapshot(notes = notes, byPath = byPath, backlinks = emptyMap())
        val backlinks = linkedMapOf<String, MutableList<String>>()
        for (note in notes) {
            for (link in note.links) {
                val target = snapshot.resolveLink(link.target) ?: continue
                backlinks.getOrPut(target.relativePath) { mutableListOf() }.add(note.relativePath)
            }
        }
        return snapshot.copy(backlinks = backlinks.mapValues { it.value.distinct() })
    }
}
