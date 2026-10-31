package ai.droidcommand.notes

import java.nio.file.Files
import java.nio.file.Path

data class VaultEntry(val name: String, val relativePath: String, val folder: Boolean)

/**
 * Folders and markdown notes under one directory on disk, or under a
 * [VaultStorage] the app supplies (a user-picked folder of plain `.md` files).
 * Names that start with a dot are reserved and never listed: `.plugins` belongs to [PluginStore], and `.saving-*.tmp`
 * is a note being written (see [write]).
 * A path that escapes the vault is refused.
 */
class NoteVault(
    private val storage: VaultStorage,
    val root: Path? = (storage as? PathVaultStorage)?.root,
) {
    constructor(root: Path) : this(PathVaultStorage(root), root)

    init {
        root?.let { Files.createDirectories(it.resolve(PluginStore.DIRECTORY)) }
    }

    fun list(folder: String = ""): List<VaultEntry> {
        val dir = checkedFolder(folder)
        return storage.list(dir)
    }

    fun read(relative: String): String = storage.read(resolveNote(relative))

    /** Whether a note with this path exists. A path that escapes the vault is refused, as everywhere else. */
    fun exists(relative: String): Boolean = storage.exists(resolveNote(relative))

    /**
     * Saves [markdown] as the note's whole text, creating the note if it is new.
     *
     * The text is written to a hidden file beside the note and then moved over it, so a crash, a kill or a full disk part-way
     * through leaves the previous text in place. Writing straight to the note truncates it first, which turned an interrupted
     * save into an empty note.
     */
    fun write(relative: String, markdown: String) {
        storage.write(resolveNote(relative), markdown)
    }

    fun createFolder(relative: String) {
        storage.createFolder(checkedFolder(relative).ifEmpty { relative }.also { require(it.isNotEmpty()) { "Folder name is required" } })
    }

    fun delete(relative: String) {
        val path = checkedAny(relative)
        require(path.isNotEmpty()) { "Refusing to delete the vault root" }
        storage.delete(path)
    }

    fun listAllMarkdown(): List<String> = storage.listAllMarkdown().filter { path ->
        runCatching { resolveNote(path) }.isSuccess
    }

    private fun resolveNote(relative: String): String {
        require(relative.endsWith(".md")) { "Notes end with .md" }
        return checkedAny(relative)
    }

    private fun checkedFolder(relative: String): String {
        if (relative.isEmpty()) return ""
        return checkedAny(relative)
    }

    private fun checkedAny(relative: String): String {
        require(!relative.startsWith("/") && '\\' !in relative) { "Absolute paths are refused" }
        val parts = if (relative.isEmpty()) emptyList() else relative.split('/')
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." }) { "Path escapes the vault" }
        require(parts.none { it.startsWith(".") }) { "Hidden names are reserved" }
        return parts.joinToString("/")
    }
}
