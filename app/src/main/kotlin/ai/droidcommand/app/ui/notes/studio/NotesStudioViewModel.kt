package ai.droidcommand.app.ui.notes.studio

import ai.droidcommand.app.brain.BrainRuntime
import ai.droidcommand.notes.studio.ContextDocument
import ai.droidcommand.notes.studio.ContextFileKind
import ai.droidcommand.notes.studio.ContextLinter
import ai.droidcommand.notes.studio.ContextSection
import ai.droidcommand.notes.studio.ContextTemplates
import ai.droidcommand.notes.studio.LanVaultPolicy
import ai.droidcommand.notes.studio.LanVaultServer
import ai.droidcommand.notes.studio.LintIssue
import ai.droidcommand.notes.studio.PromptVault
import ai.droidcommand.notes.studio.SavedPrompt
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject

/** Notes Studio: split editor, AI context files, Prompt Vault and the optional LAN viewer. */
@HiltViewModel
class NotesStudioViewModel @Inject constructor(
    private val runtime: BrainRuntime,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    var message by mutableStateOf<String?>(null)
        private set

    // ----- split editor -----
    var notes by mutableStateOf<List<String>>(emptyList())
        private set
    var openNote by mutableStateOf<String?>(null)
        private set

    fun loadNotes() {
        viewModelScope.launch { notes = withContext(Dispatchers.IO) { runCatching { runtime.vault().listAllMarkdown().sorted() }.getOrDefault(emptyList()) } }
    }

    fun open(path: String, onText: (String) -> Unit) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { runtime.vault().read(path) }.getOrNull() }
            if (text == null) {
                message = "Could not open $path"
            } else {
                openNote = path
                onText(text)
            }
        }
    }

    fun save(path: String, text: String) {
        viewModelScope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    runtime.vault().write(path, text)
                    "Saved $path"
                }.getOrElse { "Could not save: ${it.message}" }
            }
            openNote = path
            loadNotes()
        }
    }

    // ----- context files -----
    var kind by mutableStateOf(ContextFileKind.entries.first())
        private set
    var document by mutableStateOf(ContextTemplates.forKind(ContextFileKind.entries.first()).first().document)
        private set
    val rendered: String get() = document.render()
    val issues: List<LintIssue> get() = ContextLinter.lint(rendered, kind)

    fun chooseKind(k: ContextFileKind) {
        kind = k
    }

    fun applyTemplate(t: ContextTemplates.Template) {
        kind = t.kind
        document = t.document
    }

    fun setTitle(title: String) {
        document = document.copy(title = title)
    }

    fun updateSection(i: Int, s: ContextSection) {
        document = document.withSection(i, s)
    }

    fun addSection() {
        document = document.addSection(ContextSection("New section", ""))
    }

    fun removeSection(i: Int) {
        document = document.removeSection(i)
    }

    fun moveSection(from: Int, to: Int) {
        document = document.moveSection(from, to)
    }

    fun importText(text: String) {
        document = ContextDocument.parse(text)
    }

    fun saveContextToVault() = save("AI Context/" + kind.exportPath.substringAfterLast('/').trimStart('.').let { if (it.endsWith(".md")) it else "$it.md" }, rendered)

    fun exportTo(uri: android.net.Uri) {
        viewModelScope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(rendered.toByteArray()) } ?: error("no stream")
                    "Exported ${kind.label}"
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }
    }

    // ----- prompt vault -----
    private val prompts = PromptVault(context.filesDir.toPath().resolve("prompt-vault").resolve("prompts.tsv"))
    var promptQuery by mutableStateOf("")
        private set
    var promptCategory by mutableStateOf<String?>(null)
        private set
    var promptList by mutableStateOf<List<SavedPrompt>>(emptyList())
        private set
    var categories by mutableStateOf<List<String>>(emptyList())
        private set

    fun loadPrompts() {
        viewModelScope.launch {
            val (list, cats) = withContext(Dispatchers.IO) { prompts.search(promptQuery, promptCategory) to prompts.categories() }
            promptList = list
            categories = cats
        }
    }

    fun setQuery(q: String) {
        promptQuery = q
        loadPrompts()
    }

    fun setCategory(c: String?) {
        promptCategory = c
        loadPrompts()
    }

    fun savePrompt(title: String, category: String, body: String) {
        if (title.isBlank() || body.isBlank()) {
            message = "A prompt needs a title and text"
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prompts.save(title.trim(), category.trim().ifEmpty { "General" }, body) }
            loadPrompts()
        }
    }

    fun deletePrompt(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { prompts.delete(id) }
            loadPrompts()
        }
    }

    // ----- LAN viewer (off by default, never auto-started, not persisted) -----
    private var server: LanVaultServer? = null
    var lanRunning by mutableStateOf(LanVaultPolicy.DEFAULT_ENABLED)
        private set
    var lanUrl by mutableStateOf<String?>(null)
        private set

    fun startLan(password: String) {
        LanVaultPolicy.passwordProblem(password)?.let {
            message = it
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val address = lanAddress() ?: error("No Wi-Fi/LAN address found. Connect to Wi-Fi first.")
                    val mermaid = runCatching { context.assets.open("mermaid/mermaid.min.js").use { it.readBytes() } }.getOrNull()
                    val s = LanVaultServer({ runtime.vault() }, password) { mermaid }
                    s.start(address, LanVaultPolicy.DEFAULT_PORT)
                    s to "http://${address.hostAddress}:${s.port}/"
                }
            }
            result.onSuccess { (s, url) ->
                server = s
                lanUrl = url
                lanRunning = true
                message = "LAN viewer on. User name: vault"
            }
                .onFailure { message = "Could not start: ${it.message}" }
        }
    }

    fun stopLan() {
        server?.stop()
        server = null
        lanRunning = false
        lanUrl = null
    }

    fun clearMessage() {
        message = null
    }

    override fun onCleared() {
        stopLan()
        super.onCleared()
    }

    private fun lanAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && LanVaultPolicy.isLanAddress(it) }
}
