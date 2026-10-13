package ai.droidcommand.notes

import java.nio.file.Files
import java.nio.file.Path

/**
 * Vault stored as ordinary folders and `.md` files under [root]. The root is created on first use.
 * Paths are checked twice: once as text ([normalizeNotePath]) and again after filesystem resolution,
 * so a path cannot leave [root].
 */
class FileNoteVault(private val root: Path) : NoteVault {
    private val lock = Any()
    private val vaultRoot: Path = root.toAbsolutePath().normalize()

    override fun listFolders(): List<String> = synchronized(lock) {
        ensureRoot()
        val found = mutableListOf("")
        Files.walk(vaultRoot).use { stream ->
            stream.filter { Files.isDirectory(it) && it != vaultRoot }.forEach { dir ->
                val relative = vaultRoot.relativize(dir).toString().replace('\\', '/')
                if (relative.split('/').none { it.startsWith(".") }) found += relative
            }
        }
        found.sorted()
    }

    override fun listNotes(folder: String): List<NoteSummary> = synchronized(lock) {
        val dir = resolveFolder(folder)
        if (!Files.isDirectory(dir)) return emptyList()
        val files = Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }.toList()
        }
        return files.map { file ->
            val relative = vaultRoot.relativize(file).toString().replace('\\', '/')
            NoteSummary(relative, titleOf(relative, readUtf8(file)), Files.getLastModifiedTime(file).toInstant())
        }.sortedBy { it.title.lowercase() }
    }

    override fun read(path: String): VaultNote? = synchronized(lock) {
        val target = resolveNote(path)
        if (!Files.isRegularFile(target)) return null
        val relative = normalizeNotePath(path)
        toNote(relative, target)
    }

    override fun write(path: String, markdown: String): VaultNote = synchronized(lock) {
        requireMarkdownSize(markdown)
        val relative = normalizeNotePath(path)
        val target = resolveNote(relative)
        Files.createDirectories(target.parent)
        writeUtf8(target, markdown)
        toNote(relative, target)
    }

    override fun delete(path: String): Boolean = synchronized(lock) {
        val target = resolveNote(path)
        Files.deleteIfExists(target)
    }

    override fun createFolder(path: String) {
        synchronized(lock) {
            val relative = normalizeFolderPath(path)
            require(relative.isNotEmpty()) { "The vault root already exists." }
            Files.createDirectories(resolveFolder(relative))
        }
    }

    override fun deleteFolder(path: String): Boolean = synchronized(lock) {
        val relative = normalizeFolderPath(path)
        require(relative.isNotEmpty()) { "The vault root cannot be deleted." }
        val dir = resolveFolder(relative)
        if (!Files.isDirectory(dir)) return false
        val empty = Files.list(dir).use { it.findAny().isEmpty }
        if (!empty) return false
        Files.delete(dir)
        true
    }

    private fun ensureRoot() {
        Files.createDirectories(vaultRoot)
    }

    private fun resolveNote(path: String): Path = resolveInside(normalizeNotePath(path))

    private fun resolveFolder(path: String): Path {
        val relative = normalizeFolderPath(path)
        if (relative.isEmpty()) {
            ensureRoot()
            return vaultRoot
        }
        return resolveInside(relative)
    }

    private fun resolveInside(relative: String): Path {
        ensureRoot()
        val target = vaultRoot.resolve(relative).normalize()
        require(target.startsWith(vaultRoot)) { "Path must stay inside the vault." }
        return target
    }

    private fun toNote(relative: String, target: Path): VaultNote {
        val markdown = readUtf8(target)
        return VaultNote(
            path = relative,
            title = titleOf(relative, markdown),
            markdown = markdown,
            updatedAt = Files.getLastModifiedTime(target).toInstant(),
        )
    }

    private fun titleOf(relative: String, markdown: String): String {
        val heading = markdown.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#") }
        if (heading != null) {
            val text = heading.trimStart('#').trim()
            if (text.isNotEmpty()) return text
        }
        return relative.substringAfterLast('/').removeSuffix(".md")
    }
}
