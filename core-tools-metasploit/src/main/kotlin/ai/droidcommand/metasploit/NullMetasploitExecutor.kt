package ai.droidcommand.metasploit

/**
 * The always-present [MetasploitExecutor]. Same honesty rule as
 * `core-root.NullRootExecutor`/`core-termux.NullTermuxExecutor`:
 * [isAvailable] is truthfully `false` (there genuinely is no Metasploit
 * backend configured), and [execute] fails explicitly rather than
 * fabricating a successful module run.
 */
class NullMetasploitExecutor : MetasploitExecutor {
    override fun isAvailable(): Boolean = false

    override fun execute(command: MetasploitCommand, isCancelled: () -> Boolean) =
        MetasploitExecutionResult.Failure(
            "Cannot run Metasploit module '${command.modulePath}': no real Metasploit backend is configured (NullMetasploitExecutor)",
        )
}
