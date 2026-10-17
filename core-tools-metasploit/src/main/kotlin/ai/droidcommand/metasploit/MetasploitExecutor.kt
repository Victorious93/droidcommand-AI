package ai.droidcommand.metasploit

/**
 * The extension point for actually running a Metasploit module — mirrors
 * `core-root.RootExecutor`/`core-termux.TermuxExecutor`'s role in those
 * modules. [isAvailable] is meant to be called cheaply/passively, feeding
 * `core-security.SecurityPolicy` the same way those two interfaces' own
 * methods do. [NullMetasploitExecutor] is the always-present stub;
 * [ShellBackedMetasploitExecutor] is the desktop/CLI `ProcessBuilder` path;
 * [TermuxMetasploitExecutor] is the on-device path (VictorSuite Kali via
 * RUN_COMMAND). None of those have been runtime-verified against a live
 * `msfconsole` in the environments that built them.
 */
interface MetasploitExecutor {
    fun isAvailable(): Boolean

    fun execute(command: MetasploitCommand, isCancelled: () -> Boolean = { false }): MetasploitExecutionResult
}
