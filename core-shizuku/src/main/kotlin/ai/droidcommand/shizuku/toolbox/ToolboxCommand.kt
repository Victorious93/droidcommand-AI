package ai.droidcommand.shizuku.toolbox

/**
 * One System Toolbox action as an argv vector (never a shell string, except [ToolboxCommands.shell],
 * which is the terminal's own `sh -c` and is always [destructive]). [destructive] means it changes the
 * phone (or writes a file) and the UI must show [display] in a confirmation dialog before it runs.
 * Read-only actions still only run when the user taps them; nothing in the toolbox runs on its own.
 */
data class ToolboxCommand(
    val label: String,
    val argv: List<String>,
    val destructive: Boolean,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Only the root backend can run it (for example the root file browser). */
    val rootOnly: Boolean = false,
) {
    init {
        require(argv.isNotEmpty()) { "empty command" }
        require(argv.none { it.contains('\u0000') }) { "NUL in argument" }
    }

    /** The exact command, shell-quoted, as it would be typed in `adb shell`. */
    val display: String get() = ShellQuote.join(argv)

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}

/** A built command, or why the input was refused. */
sealed class ToolboxBuild {
    data class Ok(val command: ToolboxCommand) : ToolboxBuild()
    data class Rejected(val reason: String) : ToolboxBuild()
}

object ShellQuote {
    private val SAFE = Regex("^[A-Za-z0-9_@%+=:,./-]+$")

    fun quote(arg: String): String = when {
        arg.isEmpty() -> "''"
        SAFE.matches(arg) -> arg
        else -> "'" + arg.replace("'", "'\\''") + "'"
    }

    fun join(argv: List<String>): String = argv.joinToString(" ") { quote(it) }
}
