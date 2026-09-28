package ai.droidcommand.metasploit

import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor

/**
 * A real [MetasploitExecutor], not a fake — delegates the actual process
 * spawn to an injected `core-shell.ShellExecutor` rather than
 * reimplementing `ProcessBuilder` handling, the same reasoning
 * `core-build-local.LocalProcessBuildExecutor`'s own doc comment gives for
 * doing the same against `core-build.BuildExecutor`: running `msfconsole`
 * is, at the OS level, just another shell command.
 *
 * Builds a resource script from [MetasploitCommand]'s structured fields
 * only, using the standard, publicly documented msfconsole resource-script
 * commands (`use`, `set`, `run`, `exit -y`), joined with `; ` and passed via
 * `-x` (msfconsole's inline-command-string flag) so no temporary file needs
 * writing. Every dynamic value was already checked by
 * [MetasploitCommand.assertSafeInterpolationValue] at construction time, so
 * none of `use`/`set RHOSTS`/`set RPORT`/`set PAYLOAD`/`set <OPTION>` can
 * smuggle an extra `;`-joined command into the string this executor hands
 * to the shell.
 *
 * `run -z` backgrounds a successful exploit session rather than dropping
 * into an interactive shell msfconsole's own `-x` flag has no terminal to
 * attach to; `exit -y` cleanly closes msfconsole afterward without its
 * interactive confirmation prompt.
 *
 * **IMPLEMENTED — NOT RUNTIME VERIFIED**: no environment this was built in
 * has a real `msfconsole` installation, so the resource-script command
 * sequence below has not been exercised against a live Metasploit
 * Framework. It follows Metasploit's own publicly documented
 * resource-script syntax rather than a fabricated one, but should be
 * confirmed against a real installation before being trusted in place of
 * [NullMetasploitExecutor] for anything but a controlled test.
 */
class ShellBackedMetasploitExecutor(
    private val shellExecutor: ShellExecutor,
    private val msfconsolePath: String = "msfconsole",
) : MetasploitExecutor {
    override fun isAvailable(): Boolean = true

    override fun execute(command: MetasploitCommand, isCancelled: () -> Boolean): MetasploitExecutionResult {
        val lines = buildResourceScript(command)

        val shellCommand = ShellCommand(
            executable = msfconsolePath,
            args = listOf("-q", "-x", lines.joinToString("; ")),
            timeoutMillis = command.timeoutMillis,
        )

        return when (val result = shellExecutor.execute(shellCommand, isCancelled)) {
            is ShellExecutionResult.Success ->
                MetasploitExecutionResult.Success(result.exitCode, result.stdout, result.stderr, result.durationMillis)
            is ShellExecutionResult.Failure -> MetasploitExecutionResult.Failure(result.reason, result.cause)
        }
    }

    private fun buildResourceScript(command: MetasploitCommand): List<String> = buildList {
        add("use ${command.modulePath}")
        add("set RHOSTS ${command.target.host}")
        command.target.port?.let { add("set RPORT $it") }
        command.payload?.let { add("set PAYLOAD $it") }
        command.options.forEach { (key, value) -> add("set $key $value") }
        add("run -z")
        add("exit -y")
    }
}
