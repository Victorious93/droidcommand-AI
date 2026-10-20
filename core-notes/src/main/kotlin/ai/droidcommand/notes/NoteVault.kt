package ai.droidcommand.notes

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class VaultEntry(val name: String, val relativePath: String, val folder: Boolean)

/**
 * Folders and markdown notes under one directory on disk.
 * Names that start with a dot are reserved and never listed: `.plugins` belongs to [PluginStore], and `.saving-*.tmp`
 * is a note being written (see [write]).
 * A path that escapes the vault is refused.
 */
class NoteVault(val root: Path) {
    init {
        Files.createDirectories(root.resolve(PluginStore.DIRECTORY))
    }

    fun list(folder: String = ""): List<VaultEntry> {
        val dir = resolve(folder, directory = true)
        if (!Files.exists(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            // iterator().asSequence() — not Stream.toList() — so this runs on API 28
            // (Android 9 / Java 8). Stream.toList() is Java 16 and crashes Notes there.
            stream.iterator().asSequence()
                .filter { !it.fileName.toString().startsWith(".") }
                .map { path ->
                    val name = path.fileName.toString()
                    val relative = if (folder.isEmpty()) name else "$folder/$name"
                    VaultEntry(name, relative, path.isDirectory())
                }
                .sortedWith(compareBy({ !it.folder }, { it.name }))
                .toList()
        }
    }

    fun read(relative: String): String = resolveNote(relative).readText()

    /** Whether a note with this path exists. A path that escapes the vault is refused, as everywhere else. */
    fun exists(relative: String): Boolean = Files.exists(resolveNote(relative))

    /**
     * Saves [markdown] as the note's whole text, creating the note if it is new.
     *
     * The text is written to a hidden file beside the note and then moved over it, so a crash, a kill or a full disk part-way
     * through leaves the previous text in place. Writing straight to the note truncates it first, which turned an interrupted
     * save into an empty note.
     */
    fun write(relative: String, markdown: String) {
        val path = resolveNote(relative)
        val folder = path.parent
        Files.createDirectories(folder)
        val temporary = Files.createTempFile(folder, ".saving-", ".tmp")
        try {
            // Path.writeText uses Files.newOutputStream (API 26). Files.writeString is Java 11 /
            // Android API 33 and crashes Notes on this app's minSdk 28, same class of bug as
            // Stream.toList() above.
            temporary.writeText(markdown)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun createFolder(relative: String) {
        Files.createDirectories(resolve(relative, directory = true))
    }

    fun delete(relative: String) {
        val path = resolve(relative, directory = null)
        require(path != root) { "Refusing to delete the vault root" }
        if (Files.isDirectory(path)) {
            Files.walk(path).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(path)
        }
    }

    private fun resolveNote(relative: String): Path {
        require(relative.endsWith(".md")) { "Notes end with .md" }
        return resolve(relative, directory = false)
    }

    private fun resolve(relative: String, directory: Boolean?): Path {
        require(!relative.startsWith("/") && '\\' !in relative) { "Absolute paths are refused" }
        val parts = if (relative.isEmpty()) emptyList() else relative.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "Path escapes the vault" }
        require(parts.none { it.startsWith(".") }) { "Hidden names are reserved" }
        if (directory == false) {
            require(parts.last().endsWith(".md")) { "Notes end with .md" }
        }
        val path = parts.fold(root) { parent, part -> parent.resolve(part) }.normalize()
        require(path.startsWith(root)) { "Path escapes the vault" }
        return path
    }
}
