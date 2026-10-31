package ai.droidcommand.notes

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Bytes under a vault. Callers must already have refused paths that leave the vault.
 * [PathVaultStorage] is the default (app files and JVM tests). The app may pass a
 * Storage-Access-Framework implementation so the same notes can live in a user-picked folder.
 */
interface VaultStorage {
    fun list(relativeFolder: String): List<VaultEntry>

    fun read(relative: String): String

    fun write(relative: String, markdown: String)

    fun exists(relative: String): Boolean

    fun createFolder(relative: String)

    fun delete(relative: String)

    /** Relative paths of every `.md` file, folders first skipped. Hidden names are omitted. */
    fun listAllMarkdown(): List<String>
}

class PathVaultStorage(val root: Path) : VaultStorage {
    override fun list(relativeFolder: String): List<VaultEntry> {
        val dir = resolveExisting(relativeFolder) ?: return emptyList()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.iterator().asSequence()
                .filter { !it.fileName.toString().startsWith(".") }
                .map { path ->
                    val name = path.fileName.toString()
                    val relative = if (relativeFolder.isEmpty()) name else "$relativeFolder/$name"
                    VaultEntry(name, relative, path.isDirectory())
                }
                .sortedWith(compareBy({ !it.folder }, { it.name }))
                .toList()
        }
    }

    override fun read(relative: String): String = resolve(relative).readText()

    override fun write(relative: String, markdown: String) {
        val path = resolve(relative)
        val folder = path.parent
        Files.createDirectories(folder)
        val temporary = Files.createTempFile(folder, ".saving-", ".tmp")
        try {
            temporary.writeText(markdown)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun exists(relative: String): Boolean = Files.exists(resolve(relative))

    override fun createFolder(relative: String) {
        Files.createDirectories(resolve(relative))
    }

    override fun delete(relative: String) {
        val path = resolve(relative)
        require(path != root) { "Refusing to delete the vault root" }
        if (Files.isDirectory(path)) {
            Files.walk(path).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(path)
        }
    }

    override fun listAllMarkdown(): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { walk ->
            walk.iterator().asSequence()
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .filter { it.fileName.toString().startsWith(".").not() }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .filter { relative -> relative.split('/').none { part -> part.startsWith(".") } }
                .sorted()
                .toList()
        }
    }

    private fun resolveExisting(relative: String): Path? {
        val path = resolve(relative)
        return if (Files.exists(path)) path else null
    }

    private fun resolve(relative: String): Path {
        val parts = if (relative.isEmpty()) emptyList() else relative.split('/')
        return parts.fold(root) { parent, part -> parent.resolve(part) }.normalize()
    }
}
