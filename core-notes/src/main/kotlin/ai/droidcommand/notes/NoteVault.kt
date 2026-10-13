package ai.droidcommand.notes

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory

data class VaultEntry(val name: String, val relativePath: String, val folder: Boolean)

/**
 * Folders and markdown notes under one directory on disk.
 * `.plugins` is reserved for [PluginStore] and is not a note folder.
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
            stream.toList()
                .filter { it.fileName.toString() != PluginStore.DIRECTORY }
                .map { path ->
                    val name = path.fileName.toString()
                    val relative = if (folder.isEmpty()) name else "$folder/$name"
                    VaultEntry(name, relative, path.isDirectory())
                }
                .sortedWith(compareBy({ !it.folder }, { it.name }))
        }
    }

    fun read(relative: String): String = Files.readString(resolveNote(relative))

    fun write(relative: String, markdown: String) {
        val path = resolveNote(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, markdown)
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
