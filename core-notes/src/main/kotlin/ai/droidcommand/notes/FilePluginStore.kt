package ai.droidcommand.notes

import java.nio.file.Files
import java.nio.file.Path

/**
 * User-installed notes plugins, stored as `.dcplugin` files under [directory]. The directory starts
 * empty: this store never seeds a plugin, and it has no Obsidian catalog.
 *
 * Installing parses the descriptor and writes the canonical form back. Enable, disable and uninstall
 * only touch that directory. [applyEnabled] runs the enabled plugins in id order, in memory, on the
 * markdown it is given.
 */
class FilePluginStore(private val directory: Path) {
    private val lock = Any()
    private val root: Path = directory.toAbsolutePath().normalize()

    fun list(): List<InstalledPlugin> = synchronized(lock) { readAll().map { it.toInstalled() }.sortedBy { it.id } }

    fun install(source: String): InstalledPlugin = synchronized(lock) {
        val plugin = parsePluginSource(source).copy(enabled = true)
        write(plugin)
        plugin.toInstalled()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            val plugin = read(id) ?: throw IllegalArgumentException("Unknown plugin.")
            write(plugin.copy(enabled = enabled))
        }
    }

    fun uninstall(id: String): Boolean = synchronized(lock) {
        val file = fileFor(requirePluginIdPublic(id))
        Files.deleteIfExists(file)
    }

    /** Enabled plugins only, in id order. Missing or unreadable files are skipped, not executed. */
    fun applyEnabled(markdown: String): String = synchronized(lock) {
        readAll().filter { it.enabled }.sortedBy { it.id }.fold(markdown) { text, plugin -> plugin.transform(text) }
    }

    fun enabledPlugins(): List<NotePlugin> = synchronized(lock) {
        readAll().filter { it.enabled }.sortedBy { it.id }
    }

    private fun readAll(): List<DeclarativePlugin> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.list(root).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".dcplugin") }.toList()
        }.mapNotNull { file ->
            runCatching { parsePluginSource(readUtf8(file)) }.getOrNull()
        }
    }

    private fun read(id: String): DeclarativePlugin? {
        val file = fileFor(requirePluginIdPublic(id))
        if (!Files.isRegularFile(file)) return null
        return parsePluginSource(readUtf8(file))
    }

    private fun write(plugin: DeclarativePlugin) {
        Files.createDirectories(root)
        writeUtf8(fileFor(plugin.id), plugin.encode())
    }

    private fun fileFor(id: String): Path {
        val file = root.resolve("$id.dcplugin").normalize()
        require(file.parent == root) { "Plugin id cannot leave the plugin directory." }
        return file
    }
}

private fun requirePluginIdPublic(value: String): String {
    require(Regex("^[a-z0-9-]{1,40}$").matches(value) && !value.startsWith("-") && !value.endsWith("-")) {
        "Plugin id must be 1–40 characters of lowercase letters, digits or hyphens."
    }
    return value
}
