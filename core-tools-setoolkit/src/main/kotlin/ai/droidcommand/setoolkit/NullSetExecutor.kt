package ai.droidcommand.setoolkit

/**
 * The always-present [SetExecutor]. Same honesty rule as
 * `ai.droidcommand.metasploit.NullMetasploitExecutor`/`core-root.NullRootExecutor`:
 * [isAvailable] is truthfully `false`, and [execute] fails explicitly
 * rather than fabricating a successful campaign/attack run.
 */
class NullSetExecutor : SetExecutor {
    override fun isAvailable(): Boolean = false

    override fun execute(command: SetCommand, isCancelled: () -> Boolean) =
        SetExecutionResult.Failure(
            "Cannot run SET attack vector '${command.attackVector}': no real Social-Engineer Toolkit backend is configured (NullSetExecutor)",
        )
}
