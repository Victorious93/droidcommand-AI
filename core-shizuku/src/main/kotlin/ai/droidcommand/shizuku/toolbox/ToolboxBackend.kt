package ai.droidcommand.shizuku.toolbox

import ai.droidcommand.shizuku.PrivilegedResult
import ai.droidcommand.shizuku.PrivilegedShell

/** Where toolbox commands run. Shizuku (ADB shell user) or root (`su -c`). Neither runs anything on its own. */
interface ToolboxBackend {
    val name: String
    val isRoot: Boolean
    fun isAvailable(): Boolean
    fun run(command: ToolboxCommand): PrivilegedResult

    /** What the confirmation dialog shows: the exact command this backend will run. */
    fun display(command: ToolboxCommand): String
}

class ShizukuToolboxBackend(private val shell: PrivilegedShell) : ToolboxBackend {
    override val name = "Shizuku"
    override val isRoot = false
    override fun isAvailable(): Boolean = shell.isAvailable()
    override fun display(command: ToolboxCommand): String = command.display
    override fun run(command: ToolboxCommand): PrivilegedResult = when {
        command.rootOnly -> PrivilegedResult.Failure("This needs root. Shizuku runs as the ADB shell user.")
        !shell.isAvailable() -> PrivilegedResult.Failure("Shizuku is not running.")
        else -> shell.exec(command.argv, command.timeoutMs)
    }
}

/**
 * Root via `su -c '<quoted command>'`. [runner] spawns the real process (app: ProcessBuilder; tests: a fake)
 * and [probe] answers whether `su` is usable. Both are injected so this stays JVM-testable.
 */
class RootToolboxBackend(
    private val probe: () -> Boolean,
    private val runner: (argv: List<String>, timeoutMs: Long) -> PrivilegedResult,
) : ToolboxBackend {
    override val name = "Root"
    override val isRoot = true
    override fun isAvailable(): Boolean = probe()
    override fun display(command: ToolboxCommand): String = ShellQuote.join(suArgv(command))
    override fun run(command: ToolboxCommand): PrivilegedResult =
        if (!probe()) PrivilegedResult.Failure("Root is not available.") else runner(suArgv(command), command.timeoutMs)

    fun suArgv(command: ToolboxCommand): List<String> = listOf("su", "-c", command.display)
}
