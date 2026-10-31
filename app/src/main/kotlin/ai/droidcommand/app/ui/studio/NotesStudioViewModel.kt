package ai.droidcommand.app.ui.studio

import ai.droidcommand.app.brain.BrainRuntime
import ai.droidcommand.notes.NoteVault
import ai.droidcommand.notes.studio.AiContextKind
import ai.droidcommand.notes.studio.AiContextLinter
import ai.droidcommand.notes.studio.AiContextTemplates
import ai.droidcommand.notes.studio.ContextDocument
import ai.droidcommand.notes.studio.LanVaultServer
import ai.droidcommand.notes.studio.LintIssue
import ai.droidcommand.notes.studio.PromptVault
import ai.droidcommand.notes.studio.SavedPrompt
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import javax.inject.Inject

@HiltViewModel
class NotesStudioViewModel @Inject constructor(
    private val runtime: BrainRuntime,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val vault: NoteVault get() = runtime.vault()

    // AI context files
    var kind by mutableStateOf(AiContextKind.CLAUDE)
        private set
    var contextText by mutableStateOf(load(AiContextKind.CLAUDE))
        private set
    val issues: List<LintIssue> get() = AiContextLinter.lint(kind, contextText)
    val document: ContextDocument get() = ContextDocument.parse(contextText)

    fun selectKind(next: AiContextKind) {
        kind = next
        contextText = load(next)
    }

    fun editContext(text: String) {
        contextText = text
    }

    fun editDocument(doc: ContextDocument) {
        contextText = doc.render()
    }

    fun applyTemplate(project: String) {
        contextText = AiContextTemplates.template(kind, project)
    }

    fun saveContext(): String = runCatching {
        vault.write(kind.vaultPath, contextText)
        "Saved to ${kind.vaultPath} in your vault"
    }.getOrElse { "Could not save: ${it.message}" }

    /** Writes the file with its real name (for example `.cursorrules`) to the app's export folder, ready to share or copy. */
    fun exportContext(): String = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "ai-context-export").apply { mkdirs() }
        val file = File(dir, kind.fileName)
        file.writeText(contextText)
        "Exported to ${file.absolutePath}. In a repo it belongs at ${kind.repoPath}."
    }.getOrElse { "Could not export: ${it.message}" }

    private fun load(k: AiContextKind): String =
        runCatching { if (vault.exists(k.vaultPath)) vault.read(k.vaultPath) else null }.getOrNull() ?: AiContextTemplates.template(k)

    // Prompt vault
    private val prompts get() = PromptVault(vault)
    var promptQuery by mutableStateOf("")
    var promptCategory by mutableStateOf<String?>(null)
    var promptList by mutableStateOf<List<SavedPrompt>>(emptyList())
        private set
    var categories by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshPrompts() {
        runCatching {
            categories = prompts.categories()
            promptList = prompts.search(promptQuery, promptCategory)
        }
    }

    fun savePrompt(category: String, title: String, body: String): String = runCatching {
        prompts.save(category, title, body)
        refreshPrompts()
        "Saved"
    }.getOrElse { it.message ?: "Could not save" }

    fun deletePrompt(prompt: SavedPrompt) {
        runCatching { prompts.delete(prompt) }
        refreshPrompts()
    }

    // Editor
    var editorText by mutableStateOf(SAMPLE)

    fun saveEditor(path: String): String = runCatching {
        val p = path.trim().let { if (it.endsWith(".md")) it else "$it.md" }
        vault.write(p, editorText)
        "Saved $p"
    }.getOrElse { "Could not save: ${it.message}" }

    fun openEditor(path: String): String = runCatching {
        val p = path.trim().let { if (it.endsWith(".md")) it else "$it.md" }
        editorText = vault.read(p)
        "Opened $p"
    }.getOrElse { "Could not open: ${it.message}" }

    // LAN viewer: off by default, stopped when this screen goes away.
    private var server: LanVaultServer? = null
    var lanUrl by mutableStateOf<String?>(null)
        private set

    fun startLan(password: String): String {
        if (server?.isRunning == true) return "Already running"
        val address = wifiAddress() ?: return "Connect to Wi-Fi first. The viewer only listens on your local network."
        return runCatching {
            val s = LanVaultServer(vault, password, address, mermaidScript = ::mermaidJs)
            s.start()
            server = s
            lanUrl = "http://${address.hostAddress}:${s.boundPort}/"
            "Sharing read-only at $lanUrl"
        }.getOrElse { "Could not start: ${it.message}" }
    }

    fun stopLan() {
        server?.stop()
        server = null
        lanUrl = null
    }

    private fun mermaidJs(): ByteArray? = runCatching { context.assets.open("mermaid/mermaid.min.js").use { it.readBytes() } }.getOrNull()

    private fun wifiAddress(): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return cm.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.firstOrNull { it is Inet4Address && LanVaultServer.isLanAddress(it) && !it.isLoopbackAddress }
    }

    override fun onCleared() {
        stopLan()
    }

    companion object {
        val SAMPLE = """
            # New note

            Write here. The preview shows **bold**, lists and diagrams.

            ```mermaid
            graph TD
              Idea --> Draft --> Done
            ```
        """.trimIndent() + "\n"
    }
}
