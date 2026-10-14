package ai.droidcommand.notes

import java.nio.file.Files
import java.nio.file.Path

/**
 * User-installed note plugins.
 *
 * A plugin is a text manifest, not code. The host applies literal text replacements and an optional
 * prefix or suffix. It does not load classes, dex, JavaScript, or anything shipped by Obsidian.
 * A fresh vault has no plugins. Running arbitrary plugin code is not implemented.
 */
data class NotePlugin(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val prefix: String = "",
    val suffix: String = "",
    val replacements: List<Pair<String, String>> = emptyList(),
)

object PluginManifest {
    const val MAX_CHARS = 8_000
    private val ID = Regex("[a-z0-9][a-z0-9-]{0,31}")

    fun parse(text: String): NotePlugin {
        require(text.length <= MAX_CHARS) { "Manifest is too long" }
        val values = linkedMapOf<String, String>()
        val replacements = mutableListOf<Pair<String, String>>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(':')
            require(colon > 0) { "Expected key: value" }
            val key = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (key == "replace") {
                val arrow = value.indexOf("->")
                require(arrow > 0) { "replace needs find -> replacement" }
                val find = value.substring(0, arrow).trim()
                val replacement = value.substring(arrow + 2).trim()
                require(find.isNotEmpty() && find.length <= 80 && replacement.length <= 80) { "replace text is too long" }
                require(replacements.size < 20) { "Too many replacements" }
                replacements += find to replacement
            } else {
                values[key] = value
            }
        }
        val id = values["id"] ?: throw IllegalArgumentException("id is required")
        require(ID.matches(id)) { "id must be lowercase letters, digits, and dashes" }
        val name = values["name"] ?: throw IllegalArgumentException("name is required")
        require(name.isNotEmpty() && name.length <= 60) { "name is required and at most 60 characters" }
        val prefix = values["prefix"].orEmpty()
        val suffix = values["suffix"].orEmpty()
        require(prefix.length <= 200 && suffix.length <= 200) { "prefix and suffix are at most 200 characters" }
        return NotePlugin(
            id = id,
            name = name,
            enabled = values["enabled"] == "true",
            prefix = prefix,
            suffix = suffix,
            replacements = replacements,
        )
    }

    fun encode(plugin: NotePlugin): String = buildString {
        append("id: ").append(plugin.id).append('\n')
        append("name: ").append(plugin.name).append('\n')
        append("enabled: ").append(plugin.enabled).append('\n')
        if (plugin.prefix.isNotEmpty()) append("prefix: ").append(plugin.prefix).append('\n')
        if (plugin.suffix.isNotEmpty()) append("suffix: ").append(plugin.suffix).append('\n')
        plugin.replacements.forEach { (find, replacement) ->
            append("replace: ").append(find).append(" -> ").append(replacement).append('\n')
        }
    }
}

class PluginStore(private val root: Path) {
    private val dir = root.resolve(DIRECTORY)

    fun list(): List<NotePlugin> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            // iterator().asSequence() — not Stream.toList() — so this runs on API 28
            stream.iterator().asSequence()
                .filter { it.fileName.toString().endsWith(".plugin") }
                .map { PluginManifest.parse(Files.readString(it)) }
                .sortedBy { it.id }
                .toList()
        }
    }

    /** Always stores the plugin disabled. A duplicate id is refused. */
    fun install(text: String): NotePlugin {
        val parsed = PluginManifest.parse(text)
        val path = file(parsed.id)
        require(!Files.exists(path)) { "A plugin named ${parsed.id} is already installed" }
        Files.createDirectories(dir)
        val stored = parsed.copy(enabled = false)
        Files.writeString(path, PluginManifest.encode(stored))
        return stored
    }

    fun setEnabled(id: String, enabled: Boolean): NotePlugin {
        val current = read(id)
        val next = current.copy(enabled = enabled)
        Files.writeString(file(id), PluginManifest.encode(next))
        return next
    }

    fun remove(id: String) {
        Files.deleteIfExists(file(id))
    }

    /** Applies enabled plugins in id order. Literal replacements only. Disabled plugins are skipped. */
    fun apply(markdown: String): String {
        var text = markdown
        for (plugin in list().filter { it.enabled }) {
            for ((find, replacement) in plugin.replacements) {
                text = text.replace(find, replacement)
            }
            if (plugin.prefix.isNotEmpty() || plugin.suffix.isNotEmpty()) {
                text = plugin.prefix + text + plugin.suffix
            }
        }
        return text
    }

    private fun read(id: String): NotePlugin {
        val path = file(id)
        require(Files.exists(path)) { "No plugin $id" }
        return PluginManifest.parse(Files.readString(path))
    }

    private fun file(id: String): Path {
        require(PluginManifest.parse("id: $id\nname: x\n").id == id)
        return dir.resolve("$id.plugin")
    }

    companion object {
        const val DIRECTORY = ".plugins"
    }
}
