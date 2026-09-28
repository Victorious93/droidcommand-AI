package ai.droidcommand.metasploit

/**
 * The extension point for actually running a Metasploit module — mirrors
 * `core-root.RootExecutor`/`core-termux.TermuxExecutor`'s role in those
 * modules. [isAvailable] is meant to be called cheaply/passively, feeding
 * `core-security.SecurityPolicy` the same way those two interfaces' own
 * methods do. [NullMetasploitExecutor] is the only always-present
 * implementation; [ShellBackedMetasploitExecutor] is real but requires an
 * actual `msfconsole` installation reachable from wherever the injected
 * `core-shell.ShellExecutor` runs, which no environment this was built in
 * has.
 */
interface MetasploitExecutor {
    fun isAvailable(): Boolean

    fun execute(command: MetasploitCommand, isCancelled: () -> Boolean = { false }): MetasploitExecutionResult
}
