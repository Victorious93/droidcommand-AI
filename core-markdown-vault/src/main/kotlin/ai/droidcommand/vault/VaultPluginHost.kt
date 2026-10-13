package ai.droidcommand.vault

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class LiteralRewrite(val find: String, val replace: String)

/**
 * A user-installed plugin is this manifest only. [previewPrefix], [noteTemplate], and [rewrites]
 * are applied to preview text. They are not executable.
 */
@Serializable
data class VaultPluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val previewPrefix: String = "",
    val noteTemplate: String = "",
    val rewrites: List<LiteralRewrite> = emptyList(),
)

enum class PluginEnablement { DISABLED, ENABLED }

data class PluginRecord(
    val id: String,
    val manifest: VaultPluginManifest?,
    val enablement: PluginEnablement,
    val problem: String?,
)

/**
 * Plugins live as one directory under `.plugins/<id>/`. Enablement is a separate file so a
 * manifest can be installed without becoming active.
 *
 * Sandbox: manifests are data. This host does not load Dex, JavaScript, or native code.
 * That kind of plugin runtime is PLANNED and is not implemented.
 */
class VaultPluginHost(
    private val root: Path,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    },
) {
    private val pluginsDir: Path = root.resolve(PLUGINS_DIR)

    fun sandboxDescription(): String = SANDBOX

    fun list(): List<PluginRecord> {
        if (!Files.isDirectory(pluginsDir)) return emptyList()
        val found = mutableListOf<PluginRecord>()
        Files.newDirectoryStream(pluginsDir).use { stream ->
            for (path in stream) {
                if (Files.isDirectory(path)) found += record(path)
            }
        }
        return found.sortedBy { it.id }
    }

    fun install(manifest: VaultPluginManifest): PluginRecord {
        val problem = validate(manifest)
        if (problem != null) throw VaultChangeRejected(problem)
        val dir = pluginDir(manifest.id)
        if (Files.exists(dir)) throw VaultChangeRejected("A plugin named '${manifest.id}' is already installed.")
        Files.createDirectories(dir)
        writeManifest(dir, manifest)
        Files.writeString(dir.resolve(ENABLED_FILE), "false")
        return record(dir)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val dir = existingPluginDir(id)
        val current = record(dir)
        if (current.manifest == null) throw VaultChangeRejected(current.problem ?: "That plugin cannot be enabled.")
        Files.writeString(dir.resolve(ENABLED_FILE), if (enabled) "true" else "false")
    }

    fun uninstall(id: String) {
        val dir = existingPluginDir(id)
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    /** Preview text after every enabled plugin. Stored notes are not modified. */
    fun applyPreview(markdown: String): String {
        var text = markdown
        for (plugin in list()) {
            if (plugin.enablement != PluginEnablement.ENABLED) continue
            val manifest = plugin.manifest ?: continue
            if (manifest.previewPrefix.isNotEmpty()) text = manifest.previewPrefix + text
            for (rewrite in manifest.rewrites) {
                text = text.replace(rewrite.find, rewrite.replace)
            }
        }
        return text
    }

    /** Body for a new note from the first enabled template, or an empty string. */
    fun newNoteBody(title: String): String {
        val template = list().firstOrNull { it.enablement == PluginEnablement.ENABLED && !it.manifest?.noteTemplate.isNullOrBlank() }
            ?.manifest?.noteTemplate
            ?: return ""
        return template.replace(TITLE_TOKEN, title)
    }

    private fun record(dir: Path): PluginRecord {
        val id = dir.fileName.toString()
        val manifestFile = dir.resolve(MANIFEST_FILE)
        if (!Files.isRegularFile(manifestFile)) {
            return PluginRecord(id, null, PluginEnablement.DISABLED, "Missing $MANIFEST_FILE.")
        }
        val parsed = runCatching {
            json.decodeFromString(VaultPluginManifest.serializer(), Files.readString(manifestFile))
        }.getOrElse {
            return PluginRecord(id, null, PluginEnablement.DISABLED, "The manifest is not readable JSON.")
        }
        val problem = validate(parsed) ?: if (parsed.id != id) "The manifest id does not match its folder." else null
        val enabled = problem == null && Files.isRegularFile(dir.resolve(ENABLED_FILE)) &&
            Files.readString(dir.resolve(ENABLED_FILE)).trim() == "true"
        return PluginRecord(
            id = id,
            manifest = if (problem == null) parsed else null,
            enablement = if (enabled) PluginEnablement.ENABLED else PluginEnablement.DISABLED,
            problem = problem,
        )
    }

    private fun existingPluginDir(id: String): Path {
        validateId(id)?.let { throw VaultChangeRejected(it) }
        val dir = pluginDir(id)
        if (!Files.isDirectory(dir)) throw VaultChangeRejected("That plugin is not installed.")
        return dir
    }

    private fun pluginDir(id: String): Path {
        val dir = pluginsDir.resolve(id).normalize()
        if (!dir.startsWith(pluginsDir.normalize())) throw VaultChangeRejected("That plugin id is not allowed.")
        return dir
    }

    private fun writeManifest(dir: Path, manifest: VaultPluginManifest) {
        Files.writeString(dir.resolve(MANIFEST_FILE), json.encodeToString(VaultPluginManifest.serializer(), manifest))
    }

    companion object {
        const val PLUGINS_DIR = ".plugins"
        const val MANIFEST_FILE = "manifest.json"
        const val ENABLED_FILE = "enabled"
        const val TITLE_TOKEN = "{{title}}"
        const val SANDBOX =
            "Plugins are declarative manifests: a preview prefix, a note template, and literal find/replace. " +
                "They are not loaded as code. Dex, JavaScript, and native plugin runtimes are PLANNED and not implemented."

        private val ID = Regex("[a-z0-9](?:[a-z0-9-]{0,40})")
        private val VERSION = Regex("[A-Za-z0-9._-]{1,32}")

        fun validate(manifest: VaultPluginManifest): String? {
            validateId(manifest.id)?.let { return it }
            if (manifest.name.isBlank() || manifest.name.length > 80) return "Plugin name must be 1 to 80 characters."
            if (!VERSION.matches(manifest.version)) return "Plugin version must be 1 to 32 letters, numbers, dots, underscores, or hyphens."
            if (manifest.previewPrefix.length > 2000) return "Preview prefix is too long."
            if (manifest.noteTemplate.length > 2000) return "Note template is too long."
            if (manifest.rewrites.size > 20) return "A plugin can declare at most 20 rewrites."
            manifest.rewrites.forEach { rewrite ->
                if (rewrite.find.isEmpty() || rewrite.find.length > 200) return "Each find text must be 1 to 200 characters."
                if (rewrite.replace.length > 200) return "Each replacement must be at most 200 characters."
            }
            return null
        }

        private fun validateId(id: String): String? =
            if (ID.matches(id)) null else "Plugin id must be lowercase letters, numbers, and hyphens."
    }
}
