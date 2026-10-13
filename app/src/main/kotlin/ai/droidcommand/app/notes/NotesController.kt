package ai.droidcommand.app.notes

import ai.droidcommand.notes.FilePluginStore
import ai.droidcommand.notes.InstalledPlugin
import ai.droidcommand.notes.MarkdownBlock
import ai.droidcommand.notes.NotePlugin
import ai.droidcommand.notes.NoteSummary
import ai.droidcommand.notes.NoteVault
import ai.droidcommand.notes.noteFileName
import ai.droidcommand.notes.parseMarkdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class NotesUiState(
    val folders: List<String> = listOf(""),
    val folder: String = "",
    val notes: List<NoteSummary> = emptyList(),
    val openPath: String? = null,
    val draft: String = "",
    val plugins: List<InstalledPlugin> = emptyList(),
    val status: String? = null,
    val error: String? = null,
)

/**
 * App-lifetime owner of the markdown vault and the user-plugin store. Both live under app-private
 * files. The plugin store starts empty — nothing Obsidian-shaped is seeded.
 */
@Singleton
class NotesController @Inject constructor(
    private val vault: NoteVault,
    private val plugins: FilePluginStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(NotesUiState())
    val state: StateFlow<NotesUiState> = mutable

    @Volatile
    private var enabledPlugins: List<NotePlugin> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch { publish(mutable.value.folder) }
    }

    fun selectFolder(folder: String) {
        scope.launch { publish(folder) }
    }

    fun open(path: String) {
        scope.launch {
            try {
                val note = vault.read(path)
                mutable.update { current ->
                    if (note == null) {
                        current.copy(error = "That note is not in the vault.")
                    } else {
                        current.copy(openPath = note.path, draft = note.markdown, error = null, status = null)
                    }
                }
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun updateDraft(text: String) = mutable.update { it.copy(draft = text, status = null) }

    fun save() {
        val current = mutable.value
        val path = current.openPath ?: return
        scope.launch {
            try {
                vault.write(path, current.draft)
                publish(current.folder, status = "Saved $path")
                mutable.update { it.copy(openPath = path, draft = current.draft) }
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    /** Writes the enabled plugins' transform back into the open note. */
    fun applyPluginsToDraft() {
        val current = mutable.value
        val path = current.openPath ?: return
        scope.launch {
            val transformed = enabledPlugins.fold(current.draft) { text, plugin -> plugin.transform(text) }
            try {
                vault.write(path, transformed)
                mutable.update { it.copy(draft = transformed, status = "Applied enabled plugins to $path") }
                publish(current.folder, status = "Applied enabled plugins to $path")
                mutable.update { it.copy(openPath = path, draft = transformed) }
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun preview(markdown: String): List<MarkdownBlock> =
        parseMarkdown(enabledPlugins.fold(markdown) { text, plugin -> plugin.transform(text) })

    fun createNote(folder: String, rawName: String, onCreated: (String) -> Unit) {
        scope.launch {
            try {
                val path = join(folder, noteFileName(rawName))
                val title = path.substringAfterLast('/').removeSuffix(".md")
                vault.write(path, "# $title\n\n")
                publish(folder, status = "Created $path")
                withContext(Dispatchers.Main) { onCreated(path) }
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun createFolder(path: String) {
        scope.launch {
            try {
                vault.createFolder(path)
                publish(path, status = "Created folder $path")
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteNote(path: String) {
        scope.launch {
            try {
                vault.delete(path)
                if (mutable.value.openPath == path) mutable.update { it.copy(openPath = null, draft = "") }
                publish(mutable.value.folder, status = "Deleted $path")
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun installPlugin(source: String) {
        scope.launch {
            try {
                val installed = plugins.install(source)
                publish(mutable.value.folder, status = "Installed ${installed.displayName}")
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message ?: "That plugin file was rejected.") }
            }
        }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        scope.launch {
            try {
                plugins.setEnabled(id, enabled)
                publish(mutable.value.folder, status = if (enabled) "Enabled $id" else "Disabled $id")
            } catch (e: IllegalArgumentException) {
                mutable.update { it.copy(error = e.message) }
            }
        }
    }

    fun uninstallPlugin(id: String) {
        scope.launch {
            plugins.uninstall(id)
            publish(mutable.value.folder, status = "Removed $id")
        }
    }

    private fun publish(folder: String, status: String? = mutable.value.status) {
        val notes = runCatching { vault.listNotes(folder) }.getOrDefault(emptyList())
        val folders = runCatching { vault.listFolders() }.getOrElse { listOf("") }
        val listed = runCatching { plugins.list() }.getOrDefault(emptyList())
        enabledPlugins = runCatching { plugins.enabledPlugins() }.getOrDefault(emptyList())
        mutable.update {
            it.copy(
                folders = folders,
                folder = folder,
                notes = notes,
                plugins = listed,
                status = status,
                error = null,
            )
        }
    }

    private fun join(folder: String, file: String): String = if (folder.isBlank()) file else "$folder/$file"
}
