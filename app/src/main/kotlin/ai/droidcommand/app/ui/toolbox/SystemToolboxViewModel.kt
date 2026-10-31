package ai.droidcommand.app.ui.toolbox

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.app.shizuku.ToolboxRuntime
import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.toolbox.LogLevel
import ai.droidcommand.shizuku.toolbox.LogcatFilter
import ai.droidcommand.shizuku.toolbox.PackageFilter
import ai.droidcommand.shizuku.toolbox.PermissionParser
import ai.droidcommand.shizuku.toolbox.PermissionState
import ai.droidcommand.shizuku.toolbox.ToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxBuild
import ai.droidcommand.shizuku.toolbox.ToolboxCommand
import ai.droidcommand.shizuku.toolbox.ToolboxCommands
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** A command waiting for the user to read and confirm. [shown] is the exact text the backend will run. */
data class PendingCommand(val command: ToolboxCommand, val shown: String, val onDone: (String) -> Unit)

@HiltViewModel
class SystemToolboxViewModel @Inject constructor(
    private val runtime: ToolboxRuntime,
    private val prefs: AdvancedPrefs,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    var useRoot by mutableStateOf(false)
        private set
    var pending by mutableStateOf<PendingCommand?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var output by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
    var packages by mutableStateOf<List<String>>(emptyList())
        private set
    var permissions by mutableStateOf<List<PermissionState>>(emptyList())
        private set
    var logcat by mutableStateOf("")
        private set
    var aiEnabled by mutableStateOf(prefs.systemToolboxAiEnabled())
        private set

    val backend: ToolboxBackend get() = if (useRoot) runtime.root else runtime.shizuku
    fun shizukuAvailable() = runtime.shizuku.isAvailable()
    fun rootAvailable() = runtime.root.isAvailable()

    fun selectRoot(root: Boolean) {
        useRoot = root
    }

    fun setAi(enabled: Boolean) {
        prefs.setSystemToolboxAiEnabled(enabled)
        aiEnabled = enabled
    }

    /**
     * Every toolbox action goes through here. Destructive commands, and anything sent through root, wait for the
     * confirmation dialog; read-only Shizuku commands run when the user taps their button.
     */
    fun request(build: ToolboxBuild, onDone: (String) -> Unit = { output = it }) {
        when (build) {
            is ToolboxBuild.Rejected -> message = build.reason
            is ToolboxBuild.Ok -> request(build.command, onDone)
        }
    }

    fun request(command: ToolboxCommand, onDone: (String) -> Unit = { output = it }) {
        val b = backend
        if (command.rootOnly && !b.isRoot) {
            message = "This needs root. Switch the backend to Root first."
            return
        }
        if (command.destructive || b.isRoot) {
            pending = PendingCommand(command, b.display(command), onDone)
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

    private fun execute(command: ToolboxCommand, onDone: (String) -> Unit) {
        val b = backend
        busy = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { b.run(command) }
            busy = false
            onDone(format(result))
        }
    }

    fun listPackages(filter: PackageFilter) = request(ToolboxCommands.listPackages(filter)) { text ->
        packages = ToolboxCommands.parsePackages(text)
        output = "${packages.size} packages"
    }

    fun loadPermissions(pkg: String) = request(ToolboxCommands.packagePermissions(pkg)) { text ->
        permissions = PermissionParser.parse(text)
        output = if (permissions.isEmpty()) text.take(2_000) else "${permissions.size} permissions"
    }

    fun loadLogcat(level: LogLevel, lines: Int) = request(ToolboxCommands.logcat(level, lines)) { logcat = it }

    fun filteredLogcat(filter: String) = LogcatFilter.apply(logcat, filter)

    /** Saves the current (filtered) logcat to the app's own external files folder; no extra permission needed. */
    fun saveLogcat(filter: String) {
        val text = filteredLogcat(filter)
        if (text.isBlank()) {
            message = "Nothing to save. Load logcat first."
            return
        }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logcat").apply { mkdirs() }
        val file = File(dir, "logcat-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt")
        file.writeText(text)
        message = "Saved to ${file.absolutePath}"
    }

    /** First looks up the APK path (read-only), then asks before copying it to Download as <package>.apk. */
    fun backupApk(pkg: String) = request(ToolboxCommands.apkPath(pkg)) { text ->
        val source = ToolboxCommands.parseApkPaths(text).firstOrNull()
        if (source == null) {
            message = "Could not find the APK for $pkg"
        } else {
            request(ToolboxCommands.backupApk(pkg, source, BACKUP_DIR))
        }
    }

    private fun format(result: PrivilegedResult): String = when (result) {
        is PrivilegedResult.Failure -> "Failed: ${result.reason}"
        is PrivilegedResult.Success -> buildString {
            append(result.stdout)
            if (result.stderr.isNotBlank()) append("\n[stderr]\n").append(result.stderr)
            if (result.timedOut) append("\n[timed out]")
            if (result.exitCode != 0) append("\n[exit ${result.exitCode}]")
        }.trim().ifEmpty { "(no output, exit ${result.exitCode})" }
    }

    companion object {
        const val BACKUP_DIR = "/sdcard/Download"
    }
}
