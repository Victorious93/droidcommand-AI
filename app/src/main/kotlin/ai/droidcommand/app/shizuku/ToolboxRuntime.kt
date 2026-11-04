package ai.droidcommand.app.shizuku

import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.PrivilegedShell
import ai.droidcommand.shizuku.toolbox.RootToolboxBackend
import ai.droidcommand.shizuku.toolbox.ShizukuToolboxBackend
import ai.droidcommand.shizuku.toolbox.ToolboxBackend
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two ways the System Toolbox can run a command: built-in Shizuku (shell user) or root (`su -c`).
 * Root is detected only by looking for an `su` binary on disk; nothing calls `su` until the user confirms a command,
 * so merely opening the screen never triggers a root prompt.
 */
@Singleton
class ToolboxRuntime @Inject constructor(privilegedShell: PrivilegedShell) {
    val shizuku: ToolboxBackend = ShizukuToolboxBackend(privilegedShell)
    val root: ToolboxBackend = RootToolboxBackend(::suPresent, ::runProcess)

    /** The backend the assistant uses: Shizuku when it is running, else root when present, else none. */
    fun preferred(): ToolboxBackend? = when {
        shizuku.isAvailable() -> shizuku
        root.isAvailable() -> root
        else -> null
    }

    companion object {
        private val SU_PATHS = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su")

        fun suPresent(): Boolean = SU_PATHS.any { File(it).exists() }

        fun runProcess(argv: List<String>, timeoutMs: Long): PrivilegedResult = runCatching {
            val process = ProcessBuilder(argv).redirectErrorStream(false).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { out.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
            val tErr = Thread { err.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            tOut.join(1_000)
            tErr.join(1_000)
            PrivilegedResult.Success(if (finished) process.exitValue() else -1, out.toString(), err.toString(), !finished)
        }.getOrElse { PrivilegedResult.Failure("Could not run ${argv.firstOrNull()}: ${it.message}", it) }
    }
}
