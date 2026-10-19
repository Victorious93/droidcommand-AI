package ai.droidcommand.app.ui.automation

import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.app.automation.AutomationAction
import ai.droidcommand.app.automation.AutomationKey
import ai.droidcommand.app.automation.AutomationKeyRegistry
import ai.droidcommand.app.automation.AutomationRunResult
import ai.droidcommand.app.automation.AutomationRunner
import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.llm.factory.AiSlashCommands
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class AutomationUiState(
    val keys: List<AutomationKey> = emptyList(),
    val allowedExecutables: Set<String> = emptySet(),
    /** True from the moment a run starts until it finishes, including while the approval dialog is open. */
    val running: Boolean = false,
    val lastRunLabel: String? = null,
    val lastRun: AutomationRunResult? = null,
    /** Why the last edit was refused, or what it did. */
    val message: String? = null,
)

/**
 * Presents automation keys for [AutomationScreen]. Keys live in app-private storage. A run blocks until the owner answers
 * the approval dialog, so it is dispatched on [Dispatchers.IO], never the main thread.
 */
@HiltViewModel
class AutomationViewModel @Inject constructor(
    @ApplicationContext context: Context,
    toolRunner: ToolRunner,
    private val advanced: AdvancedPrefs,
) : ViewModel() {
    private val registry = AutomationKeyRegistry(File(context.filesDir, "automation"))
    private val keyRunner = AutomationRunner(toolRunner) { advanced.shellAllowlist() }

    // Saves take turns, so two quick edits cannot each write over the other's list.
    private val storage = Mutex()

    private val mutableState = MutableStateFlow(AutomationUiState(allowedExecutables = advanced.shellAllowlist()))
    val state: StateFlow<AutomationUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val keys = storage.withLock { runCatching { registry.load() }.getOrDefault(emptyList()) }
            mutableState.update { it.copy(keys = keys) }
        }
    }

    /** Validates the form and saves the key. Nothing is saved when the form is refused. */
    fun addKey(label: String, shell: Boolean, executable: String, args: String, slashCommand: String) {
        val cleanLabel = label.trim()
        if (cleanLabel.isEmpty()) return refuse("Give the key a label.")
        val action = if (shell) {
            val program = executable.trim()
            if (program.isEmpty()) return refuse("Enter the executable to run, written exactly as you will allow it below.")
            AutomationAction.Shell(program, args.trim())
        } else {
            when (val parsed = AiSlashCommands.parse(slashCommand)) {
                is AiSlashCommands.Parsed.Command -> AutomationAction.Ai(parsed.providerId, parsed.prompt)
                is AiSlashCommands.Parsed.MissingPrompt -> return refuse("Add a prompt after /${parsed.command}.")
                AiSlashCommands.Parsed.NotCommand -> return refuse("Start with a command: /claude, /chatgpt, /gemini, /groq, /grok or /openrouter.")
            }
        }
        val key = AutomationKey(UUID.randomUUID().toString(), cleanLabel, action)
        viewModelScope.launch(Dispatchers.IO) {
            storage.withLock { persist(mutableState.value.keys + key, message = "Saved '${key.label}'.") }
        }
    }

    fun deleteKey(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            storage.withLock { persist(mutableState.value.keys.filterNot { it.id == id }, message = null) }
        }
    }

    fun runKey(id: String) {
        val current = mutableState.value
        if (current.running) return
        val key = current.keys.firstOrNull { it.id == id } ?: return
        mutableState.update { it.copy(running = true, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { keyRunner.run(key) }
                .getOrElse { AutomationRunResult(false, it.message ?: "The key could not run.") }
            mutableState.update {
                it.copy(
                    running = false,
                    lastRunLabel = key.label,
                    lastRun = result.copy(text = result.text.take(MAX_RESULT_CHARS)),
                )
            }
        }
    }

    fun addAllowedExecutable(raw: String) {
        val entry = raw.trim()
        when {
            entry.isEmpty() -> refuse("Enter an executable to allow.")
            '\n' in entry || '\r' in entry -> refuse("An executable name cannot contain a line break.")
            entry.length > MAX_ENTRY_CHARS -> refuse("That entry is too long.")
            else -> saveAllowlist(mutableState.value.allowedExecutables + entry, "Allowed '$entry'.")
        }
    }

    fun removeAllowedExecutable(entry: String) {
        saveAllowlist(mutableState.value.allowedExecutables - entry, "Removed '$entry'.")
    }

    private fun saveAllowlist(next: Set<String>, message: String) {
        advanced.setShellAllowlist(next)
        mutableState.update { it.copy(allowedExecutables = next, message = message) }
    }

    private fun refuse(message: String) {
        mutableState.update { it.copy(message = message) }
    }

    /** Writes the list. A storage failure is shown, not thrown, so it cannot crash the app. */
    private fun persist(keys: List<AutomationKey>, message: String?) {
        val saved = runCatching { registry.save(keys) }.isSuccess
        mutableState.update {
            if (saved) it.copy(keys = keys, message = message) else it.copy(message = "The keys could not be saved to storage.")
        }
    }

    private companion object {
        const val MAX_RESULT_CHARS = 4_000
        const val MAX_ENTRY_CHARS = 512
    }
}
