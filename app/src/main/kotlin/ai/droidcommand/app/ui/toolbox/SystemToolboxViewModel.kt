package ai.droidcommand.app.ui.toolbox

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.shizuku.toolbox.AppToolboxRunner
import ai.droidcommand.shizuku.toolbox.ToolboxCommand
import ai.droidcommand.shizuku.toolbox.ToolboxCommands
import ai.droidcommand.shizuku.toolbox.ToolboxRunResult
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** A command waiting for the user to read its exact text and tap Run. */
data class PendingCommand(val command: ToolboxCommand, val shownText: String, val onDone: (ToolboxRunResult) -> Unit)

/**
 * State for the System Toolbox. Nothing runs on open; every run starts from a tap. Commands that
 * change the system go to [pending] first, and only [confirm] runs them.
 */
@HiltViewModel
class SystemToolboxViewModel @Inject constructor(
    private val runner: AppToolboxRunner,
    private val advanced: AdvancedPrefs,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    var output by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingCommand?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var packages by mutableStateOf<List<String>>(emptyList())
        private set
    var permissions by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
        private set
    var log by mutableStateOf("")
        private set
    var aiEnabled by mutableStateOf(advanced.toolboxAiEnabled())
        private set
    var confirmAiGate by mutableStateOf(false)
        private set

    /** Builds a command (may throw on bad input) and runs it, asking first when it changes the system. */
    fun request(build: () -> ToolboxCommand, onDone: (ToolboxRunResult) -> Unit = { output = it.output.ifBlank { "(exit ${it.exitCode})" } }) {
        val command = try {
            build()
        } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        error = null
        if (command.changesSystem) {
            pending = PendingCommand(command, command.displayFor(runner.backendFor(command)), onDone)
        } else {
            execute(command, onDone)
        }
    }

    fun confirm() {
        val p = pending ?: return
        pending = null
        execute(p.command, p.onDone)
    }

    fun cancel() {
        pending = null
    }

    fun loadPackages(filter: ai.droidcommand.shizuku.toolbox.PackageFilter) =
        request({ ToolboxCommands.listPackages(filter) }) {
            packages = ToolboxCommands.parsePackages(it.output)
            output = if (it.ok) "${packages.size} apps" else it.output
        }

    fun loadPermissions(pkg: String) =
        request({ ToolboxCommands.packageInfo(pkg) }) {
            permissions = ToolboxCommands.parsePermissions(it.output)
            output = if (it.ok) "${permissions.size} permissions" else it.output
        }

    fun loadLog(tag: String, level: String) = request({ ToolboxCommands.logcat(tag.ifBlank { null }, level) }) { log = it.output }

    fun saveLog(text: String) {
        viewModelScope.launch {
            output = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logcat").apply { mkdirs() }
                    val f = File(dir, "logcat-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt")
                    f.writeText(text)
                    "Saved to ${f.absolutePath}"
                }.getOrElse { "Could not save: ${it.message}" }
            }
        }
    }

    /** Looks up the base APK path (read-only), then asks before copying it to Download. */
    fun backupApk(pkg: String) = request({ ToolboxCommands.apkPath(pkg) }) { r ->
        val base = ToolboxCommands.parseApkPaths(r.output).firstOrNull { it.endsWith("base.apk") } ?: ToolboxCommands.parseApkPaths(r.output).firstOrNull()
        if (base == null) output = "No APK path found: ${r.output}" else request({ ToolboxCommands.backupApk(pkg, base, BACKUP_DIR) })
    }

    fun requestAiGate(enabled: Boolean) {
        if (enabled) confirmAiGate = true else setAi(false)
    }

    fun confirmAi(yes: Boolean) {
        confirmAiGate = false
        if (yes) setAi(true)
    }

    private fun setAi(enabled: Boolean) {
        advanced.setToolboxAiEnabled(enabled)
        aiEnabled = enabled
    }

    private fun execute(command: ToolboxCommand, onDone: (ToolboxRunResult) -> Unit) {
        busy = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runner.run(command) }
            busy = false
            onDone(r)
        }
    }

    companion object {
        const val BACKUP_DIR = "/sdcard/Download"
    }
}
