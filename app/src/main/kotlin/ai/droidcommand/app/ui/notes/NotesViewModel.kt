package ai.droidcommand.app.ui.notes

import ai.droidcommand.app.notes.NotesController
import ai.droidcommand.notes.MarkdownBlock
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val controller: NotesController,
) : ViewModel() {
    val state = controller.state

    fun refresh() = controller.refresh()
    fun selectFolder(folder: String) = controller.selectFolder(folder)
    fun open(path: String) = controller.open(path)
    fun updateDraft(text: String) = controller.updateDraft(text)
    fun save() = controller.save()
    fun applyPlugins() = controller.applyPluginsToDraft()
    fun preview(markdown: String): List<MarkdownBlock> = controller.preview(markdown)
    fun createNote(folder: String, name: String, onCreated: (String) -> Unit) = controller.createNote(folder, name, onCreated)
    fun createFolder(path: String) = controller.createFolder(path)
    fun deleteNote(path: String) = controller.deleteNote(path)
    fun installPlugin(source: String) = controller.installPlugin(source)
    fun setPluginEnabled(id: String, enabled: Boolean) = controller.setPluginEnabled(id, enabled)
    fun uninstallPlugin(id: String) = controller.uninstallPlugin(id)
}
