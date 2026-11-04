package ai.droidcommand.app.brain

import ai.droidcommand.notes.VaultEntry
import ai.droidcommand.notes.VaultStorage
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Vault IO against a folder the owner picked (Storage Access Framework).
 * Used so the same markdown can live in a folder a desktop notes app can open.
 * Never run on a device in the session that added it — Path vaults are the JVM-tested path.
 */
class SafVaultStorage(
    private val resolver: ContentResolver,
    treeUri: Uri,
    context: Context,
) : VaultStorage {
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: error("Could not open that folder")

    override fun list(relativeFolder: String): List<VaultEntry> {
        val dir = walkTo(relativeFolder, create = false) ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .filter { it.name?.startsWith(".").orFalse().not() }
            .map { child ->
                val name = child.name ?: return@map null
                val relative = if (relativeFolder.isEmpty()) name else "$relativeFolder/$name"
                VaultEntry(name, relative, child.isDirectory)
            }
            .filterNotNull()
            .sortedWith(compareBy({ !it.folder }, { it.name }))
    }

    override fun read(relative: String): String {
        val file = walkTo(relative, create = false) ?: error("Missing $relative")
        resolver.openInputStream(file.uri)?.bufferedReader().use { reader ->
            return reader?.readText() ?: error("Could not read $relative")
        }
    }

    override fun write(relative: String, markdown: String) {
        val file = walkTo(relative, create = true) ?: error("Could not create $relative")
        resolver.openOutputStream(file.uri, "wt")?.bufferedWriter().use { writer ->
            writer?.write(markdown) ?: error("Could not write $relative")
        }
    }

    override fun exists(relative: String): Boolean = walkTo(relative, create = false) != null

    override fun createFolder(relative: String) {
        walkTo(relative, create = true, directory = true)
    }

    override fun delete(relative: String) {
        walkTo(relative, create = false)?.delete()
    }

    override fun listAllMarkdown(): List<String> = collectMarkdown(root, "")

    private fun collectMarkdown(dir: DocumentFile, prefix: String): List<String> {
        val out = mutableListOf<String>()
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                out += collectMarkdown(child, relative)
            } else if (name.endsWith(".md")) {
                out += relative
            }
        }
        return out.sorted()
    }

    private fun walkTo(relative: String, create: Boolean, directory: Boolean = false): DocumentFile? {
        if (relative.isEmpty()) return root
        val parts = relative.split('/')
        var current = root
        for ((index, part) in parts.withIndex()) {
            val last = index == parts.lastIndex
            val wantDir = !last || directory
            val existing = current.listFiles().firstOrNull { it.name == part }
            if (existing != null) {
                current = existing
                continue
            }
            if (!create) return null
            current = if (wantDir) {
                current.createDirectory(part) ?: return null
            } else {
                val stem = part.removeSuffix(".md")
                current.createFile("text/markdown", stem) ?: return null
            }
        }
        return current
    }

    private fun Boolean?.orFalse(): Boolean = this == true
}
