package ai.droidcommand.notes

import java.time.Instant

/** A markdown note stored inside a vault. [path] is relative to the vault root and ends in `.md`. */
data class VaultNote(
    val path: String,
    val title: String,
    val markdown: String,
    val updatedAt: Instant,
)

/** A note listed without its body. */
data class NoteSummary(
    val path: String,
    val title: String,
    val updatedAt: Instant,
)

/**
 * An Obsidian-style vault: folders and markdown notes under one root. Implementations must refuse
 * paths that escape the root. This is not an Obsidian vault format and it does not read Obsidian
 * config or plugins.
 */
interface NoteVault {
    fun listFolders(): List<String>

    fun listNotes(folder: String): List<NoteSummary>

    fun read(path: String): VaultNote?

    fun write(path: String, markdown: String): VaultNote

    fun delete(path: String): Boolean

    fun createFolder(path: String)

    fun deleteFolder(path: String): Boolean
}
