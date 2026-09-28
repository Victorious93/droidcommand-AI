package ai.droidcommand.setoolkit

/**
 * The extension point for actually running a Social-Engineer Toolkit
 * attack vector — mirrors `ai.droidcommand.metasploit.MetasploitExecutor`'s
 * role in that module. [NullSetExecutor] is the only always-present
 * implementation; [ProcessBackedSetExecutor] is real but requires an
 * actual `setoolkit` installation reachable from wherever the injected
 * `core-shell.ShellExecutor` runs, which no environment this was built in
 * has.
 */
interface SetExecutor {
    fun isAvailable(): Boolean

    fun execute(command: SetCommand, isCancelled: () -> Boolean = { false }): SetExecutionResult
}
