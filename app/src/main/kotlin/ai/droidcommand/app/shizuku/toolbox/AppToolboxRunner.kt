package ai.droidcommand.app.shizuku.toolbox

import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.PrivilegedShell
import ai.droidcommand.shizuku.toolbox.ToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxCommand
import ai.droidcommand.shizuku.toolbox.ToolboxRunResult
import ai.droidcommand.shizuku.toolbox.ToolboxRunner
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs System Toolbox commands through the existing Shizuku UserService, or through `su -c`
 * when the command needs root or Shizuku is not connected. It never decides on its own to run
 * anything: callers (the screen after its confirm dialog, or the AI tool after the approval
 * prompt) pass one command at a time.
 */
@Singleton
class AppToolboxRunner @Inject constructor(
    private val shell: PrivilegedShell,
) : ToolboxRunner {
    fun backendFor(command: ToolboxCommand): ToolboxBackend =
        if (!command.needsRoot && shizukuAvailable()) ToolboxBackend.SHIZUKU else ToolboxBackend.ROOT

    fun shizukuAvailable(): Boolean = runCatching { shell.isAvailable() }.getOrDefault(false)

    override fun run(command: ToolboxCommand): ToolboxRunResult = when (backendFor(command)) {
        ToolboxBackend.SHIZUKU -> when (val r = shell.exec(command.argvFor(ToolboxBackend.SHIZUKU), TIMEOUT_MS)) {
            is PrivilegedResult.Success -> ToolboxRunResult(r.exitCode == 0 && !r.timedOut, r.exitCode, join(r.stdout, r.stderr, r.timedOut))
            is PrivilegedResult.Failure -> ToolboxRunResult(false, -1, r.reason)
        }
        ToolboxBackend.ROOT -> runRoot(command.argvFor(ToolboxBackend.ROOT))
    }

    private fun runRoot(argv: List<String>): ToolboxRunResult = try {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
        val done = p.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!done) p.destroyForcibly()
        reader.join(2_000)
        val code = if (done) p.exitValue() else -1
        ToolboxRunResult(done && code == 0, code, out.toString().take(MAX_OUT) + if (done) "" else "\n[timed out]")
    } catch (e: Exception) {
        ToolboxRunResult(false, -1, "Root is not available: ${e.message}")
    }

    private fun join(out: String, err: String, timedOut: Boolean) =
        (out + if (err.isNotBlank()) "\n$err" else "").take(MAX_OUT) + if (timedOut) "\n[timed out]" else ""

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val MAX_OUT = 512_000
    }
}
