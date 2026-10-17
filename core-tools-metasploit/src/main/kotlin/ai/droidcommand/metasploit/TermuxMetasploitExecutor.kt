package ai.droidcommand.metasploit

import ai.droidcommand.termux.KaliRunCommandEnvelope
import ai.droidcommand.termux.ProotDistroKaliEnvelope
import ai.droidcommand.termux.TermuxExecutionResult
import ai.droidcommand.termux.TermuxExecutor

/**
 * [MetasploitExecutor] that runs `msfconsole -q -r <resource script>`
 * **inside VictorSuite's Kali proot** via [TermuxExecutor] /
 * `run_termux_command` / `com.termux.RUN_COMMAND`.
 *
 * Kali wrapping lives entirely in [KaliRunCommandEnvelope] so a change to
 * the proot-distro argv does not touch this class. This executor never
 * shells out through `core-shell` [ProcessBuilder]; that path stays on
 * [ShellBackedMetasploitExecutor] for desktop/CLI only.
 *
 * **IMPLEMENTED — NOT RUNTIME VERIFIED.** No session that built this has
 * run msfconsole on a device. A successful [TermuxExecutionResult.Success]
 * from a fake [TermuxExecutor] in tests is not a live Metasploit round-trip.
 */
class TermuxMetasploitExecutor(
    private val termux: TermuxExecutor,
    private val envelope: KaliRunCommandEnvelope = ProotDistroKaliEnvelope(),
) : MetasploitExecutor {
    override fun isAvailable(): Boolean = termux.isAvailable()

    override fun execute(command: MetasploitCommand, isCancelled: () -> Boolean): MetasploitExecutionResult {
        if (!isAvailable()) {
            return MetasploitExecutionResult.Failure(
                "Cannot run Metasploit module '${command.modulePath}': VictorSuite RUN_COMMAND is not available",
            )
        }
        val script = MetasploitResourceScript.lines(command)
        val termuxCommand = envelope.msfconsoleResourceScript(script, command.timeoutMillis)
        return when (val result = termux.execute(termuxCommand, isCancelled)) {
            is TermuxExecutionResult.Success ->
                MetasploitExecutionResult.Success(result.exitCode, result.stdout, result.stderr, result.durationMillis)
            is TermuxExecutionResult.Failure -> MetasploitExecutionResult.Failure(result.reason, result.cause)
        }
    }
}
