package ai.droidcommand.vault

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo

class VaultChangeRejected(message: String) : IllegalArgumentException(message)

data class VaultFolder(val id: String, val name: String, val parentId: String?)

data class VaultNote(
    val id: String,
    val folderId: String?,
    val title: String,
    val markdown: String,
    val updatedAtEpochMs: Long,
)

/**
 * A folder tree of markdown notes on the local filesystem. Names are a single path segment.
 * Nothing outside [root] is read or written.
 */
class FileMarkdownVault(private val root: Path) {
    init {
        Files.createDirectories(root)
    }

    fun folders(): List<VaultFolder> {
        if (!Files.isDirectory(root)) return emptyList()
        val found = mutableListOf<VaultFolder>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it) && it != root }
                .forEach { dir ->
                    val relative = relativeId(dir)
                    if (hidden(relative)) return@forEach
                    val parent = dir.parent
                    val parentId = if (parent == root) null else relativeId(parent)
                    found += VaultFolder(relative, dir.fileName.toString(), parentId)
                }
        }
        return found.sortedBy { it.id }
    }

    fun notes(folderId: String?): List<VaultNote> {
        val dir = directory(folderId)
        if (!Files.isDirectory(dir)) return emptyList()
        val found = mutableListOf<VaultNote>()
        Files.newDirectoryStream(dir).use { stream ->
            for (path in stream) {
                if (Files.isRegularFile(path) && path.fileName.toString().endsWith(NOTE_SUFFIX)) {
                    found += readNote(path)
                }
            }
        }
        return found.sortedBy { it.title }
    }

    fun note(id: String): VaultNote? {
        val path = resolveExistingNote(id) ?: return null
        return readNote(path)
    }

    fun createFolder(name: String, parentId: String?): VaultFolder {
        val clean = checkName(name)
        val parent = directory(parentId)
        if (!Files.isDirectory(parent)) throw VaultChangeRejected("That folder does not exist.")
        val dir = parent.resolve(clean)
        if (Files.exists(dir)) throw VaultChangeRejected("A folder named '$clean' is already there.")
        Files.createDirectory(dir)
        val id = relativeId(dir)
        val storedParent = if (parent == root) null else relativeId(parent)
        return VaultFolder(id, clean, storedParent)
    }

    fun createNote(folderId: String?, title: String, markdown: String): VaultNote {
        val clean = checkName(title)
        checkMarkdown(markdown)
        val dir = directory(folderId)
        if (!Files.isDirectory(dir)) throw VaultChangeRejected("That folder does not exist.")
        val path = dir.resolve(clean + NOTE_SUFFIX)
        if (Files.exists(path)) throw VaultChangeRejected("A note named '$clean' is already there.")
        Files.writeString(path, markdown)
        return readNote(path)
    }

    fun updateNote(id: String, title: String, markdown: String): VaultNote {
        val current = resolveExistingNote(id) ?: throw VaultChangeRejected("That note does not exist.")
        val clean = checkName(title)
        checkMarkdown(markdown)
        val destination = current.parent.resolve(clean + NOTE_SUFFIX)
        if (destination != current && Files.exists(destination)) {
            throw VaultChangeRejected("A note named '$clean' is already there.")
        }
        Files.writeString(current, markdown)
        if (destination != current) Files.move(current, destination)
        return readNote(destination)
    }

    fun deleteNote(id: String) {
        val path = resolveExistingNote(id) ?: throw VaultChangeRejected("That note does not exist.")
        Files.delete(path)
    }

    fun deleteFolder(id: String) {
        val dir = directory(id)
        if (dir == root || !Files.isDirectory(dir)) throw VaultChangeRejected("That folder does not exist.")
        val leftover = Files.newDirectoryStream(dir).use { stream -> stream.iterator().hasNext() }
        if (leftover) throw VaultChangeRejected("Move or delete everything in that folder first.")
        Files.delete(dir)
    }

    private fun directory(folderId: String?): Path {
        if (folderId == null) return root
        if (hidden(folderId)) throw VaultChangeRejected("That folder does not exist.")
        val dir = resolveInside(folderId)
        return dir
    }

    private fun resolveExistingNote(id: String): Path? {
        if (!id.endsWith(NOTE_SUFFIX) || hidden(id)) return null
        val path = resolveInside(id)
        return path.takeIf { Files.isRegularFile(it) }
    }

    private fun resolveInside(relative: String): Path {
        val normalized = root.resolve(relative).normalize()
        if (!normalized.startsWith(root)) throw VaultChangeRejected("That path is outside the vault.")
        return normalized
    }

    private fun relativeId(path: Path): String = path.relativeTo(root).toString().replace('\\', '/')

    private fun readNote(path: Path): VaultNote {
        val relative = relativeId(path)
        val parent = path.parent
        val folderId = if (parent == root) null else relativeId(parent)
        val title = path.fileName.toString().removeSuffix(NOTE_SUFFIX)
        return VaultNote(
            id = relative,
            folderId = folderId,
            title = title,
            markdown = Files.readString(path),
            updatedAtEpochMs = Files.getLastModifiedTime(path).toMillis(),
        )
    }

    private fun checkName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw VaultChangeRejected("Name is empty.")
        if (trimmed != name) throw VaultChangeRejected("Name has surrounding spaces.")
        if (trimmed.length > MAX_NAME) throw VaultChangeRejected("Name is too long.")
        if (!NAME.matches(trimmed)) {
            throw VaultChangeRejected("Use letters, numbers, spaces, dots, underscores, or hyphens.")
        }
        return trimmed
    }

    private fun checkMarkdown(markdown: String) {
        if (markdown.length > MAX_MARKDOWN) throw VaultChangeRejected("That note is too long.")
    }

    private fun hidden(relative: String): Boolean = relative.split('/').any { it.isEmpty() || it.startsWith(".") || it == ".." }

    companion object {
        const val NOTE_SUFFIX = ".md"
        const val MAX_NAME = 80
        const val MAX_MARKDOWN = 1_000_000
        private val NAME = Regex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}")
    }
}
