package ai.droidcommand.app.ui.vault

import ai.droidcommand.vault.FileMarkdownVault
import ai.droidcommand.vault.MarkdownPreview
import ai.droidcommand.vault.PluginEnablement
import ai.droidcommand.vault.PluginRecord
import ai.droidcommand.vault.VaultChangeRejected
import ai.droidcommand.vault.VaultFolder
import ai.droidcommand.vault.VaultNote
import ai.droidcommand.vault.VaultPluginHost
import ai.droidcommand.vault.VaultPluginManifest
import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class VaultUiState(
    val folders: List<VaultFolder> = emptyList(),
    val folderId: String? = null,
    val notes: List<VaultNote> = emptyList(),
    val selectedNoteId: String? = null,
    val title: String = "",
    val markdown: String = "",
    val preview: Boolean = false,
    val plugins: List<PluginRecord> = emptyList(),
    val sandbox: String = "",
    val status: String? = null,
)

/**
 * Local markdown vault plus the declarative plugin host. Notes are files under app-private storage.
 * Compiles; not run on a device.
 */
@HiltViewModel
class VaultViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val root = context.filesDir.toPath().resolve("markdown-vault")
    private val vault = FileMarkdownVault(root)
    private val plugins = VaultPluginHost(root)
    private val mutableState = MutableStateFlow(
        load(folderId = null, selectedNoteId = null, keepDraft = false, previous = null),
    )
    val state: StateFlow<VaultUiState> = mutableState.asStateFlow()

    fun selectFolder(folderId: String?) {
        mutableState.value = refresh(folderId = folderId, selectedNoteId = null, keepDraft = false)
    }

    fun selectNote(id: String) {
        val note = vault.note(id) ?: return
        mutableState.update {
            it.copy(selectedNoteId = note.id, title = note.title, markdown = note.markdown, folderId = note.folderId, status = null)
        }
    }

    fun edit(title: String, markdown: String) {
        mutableState.update { it.copy(title = title, markdown = markdown) }
    }

    fun setPreview(enabled: Boolean) = mutableState.update { it.copy(preview = enabled) }

    fun createFolder(name: String) = guard {
        vault.createFolder(name, mutableState.value.folderId)
        "Created folder '$name'."
    }

    fun createNote() = guard {
        val folderId = mutableState.value.folderId
        val title = mutableState.value.title.ifBlank { "Untitled" }
        val body = plugins.newNoteBody(title)
        val note = vault.createNote(folderId, title, body)
        mutableState.value = refresh(folderId = folderId, selectedNoteId = note.id)
        "Created '${note.title}'."
    }

    fun save() = guard {
        val state = mutableState.value
        val id = state.selectedNoteId
        if (id == null) {
            "Select or create a note first."
        } else {
            val note = vault.updateNote(id, state.title, state.markdown)
            mutableState.value = refresh(folderId = note.folderId, selectedNoteId = note.id).copy(title = note.title, markdown = note.markdown)
            "Saved '${note.title}'."
        }
    }

    fun deleteSelected() = guard {
        val id = mutableState.value.selectedNoteId
        if (id == null) {
            "No note selected."
        } else {
            val folderId = mutableState.value.folderId
            vault.deleteNote(id)
            mutableState.value = refresh(folderId = folderId, selectedNoteId = null, keepDraft = false)
            "Note deleted."
        }
    }

    fun deleteFolder() = guard {
        val id = mutableState.value.folderId
        if (id == null) {
            "The vault root cannot be deleted."
        } else {
            vault.deleteFolder(id)
            mutableState.value = refresh(folderId = null, selectedNoteId = null, keepDraft = false)
            "Folder deleted."
        }
    }

    fun installPlugin(id: String, name: String, version: String, prefix: String, template: String) = guard {
        plugins.install(
            VaultPluginManifest(
                id = id.trim(),
                name = name.trim(),
                version = version.trim(),
                previewPrefix = prefix,
                noteTemplate = template,
            ),
        )
        mutableState.value = refresh(keepDraft = true)
        "Installed '$id'. It stays off until you enable it."
    }

    fun setPluginEnabled(id: String, enabled: Boolean) = guard {
        plugins.setEnabled(id, enabled)
        mutableState.value = refresh(keepDraft = true)
        if (enabled) "Enabled '$id'." else "Disabled '$id'."
    }

    fun uninstallPlugin(id: String) = guard {
        plugins.uninstall(id)
        mutableState.value = refresh(keepDraft = true)
        "Removed '$id'."
    }

    fun previewBlocks() = MarkdownPreview.parse(plugins.applyPreview(mutableState.value.markdown))

    private fun guard(block: () -> String) {
        val status = try {
            block()
        } catch (e: VaultChangeRejected) {
            e.message ?: "That change was rejected."
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        mutableState.update { it.copy(status = status, plugins = plugins.list(), sandbox = plugins.sandboxDescription()) }
    }

    private fun refresh(
        folderId: String? = mutableState.value.folderId,
        selectedNoteId: String? = mutableState.value.selectedNoteId,
        keepDraft: Boolean = true,
    ): VaultUiState = load(folderId, selectedNoteId, keepDraft, mutableState.value)

    private fun load(
        folderId: String?,
        selectedNoteId: String?,
        keepDraft: Boolean,
        previous: VaultUiState?,
    ): VaultUiState {
        val notes = runCatching { vault.notes(folderId) }.getOrDefault(emptyList())
        val selected = notes.firstOrNull { it.id == selectedNoteId }
        return VaultUiState(
            folders = runCatching { vault.folders() }.getOrDefault(emptyList()),
            folderId = folderId,
            notes = notes,
            selectedNoteId = selected?.id,
            title = if (keepDraft && previous != null && selected?.id == previous.selectedNoteId) previous.title else selected?.title.orEmpty(),
            markdown = if (keepDraft && previous != null && selected?.id == previous.selectedNoteId) previous.markdown else selected?.markdown.orEmpty(),
            preview = previous?.preview == true,
            plugins = runCatching { plugins.list() }.getOrDefault(emptyList()),
            sandbox = plugins.sandboxDescription(),
            status = previous?.status,
        )
    }
}

fun PluginRecord.switchLabel(): String =
    if (enablement == PluginEnablement.ENABLED) "On" else "Off"
