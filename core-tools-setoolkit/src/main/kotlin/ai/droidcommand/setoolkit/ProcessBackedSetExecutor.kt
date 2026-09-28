package ai.droidcommand.setoolkit

import ai.droidcommand.shell.ShellCommand
import ai.droidcommand.shell.ShellExecutionResult
import ai.droidcommand.shell.ShellExecutor
import java.nio.file.Files
import java.nio.file.Path

/**
 * A real [SetExecutor] — delegates the actual process spawn to an injected
 * `core-shell.ShellExecutor`, the same `core-build-local.LocalProcessBuildExecutor`
 * precedent `ai.droidcommand.metasploit.ShellBackedMetasploitExecutor` follows.
 *
 * **Deliberately more conservative than its Metasploit sibling.** Metasploit's
 * `msfconsole -x`/resource-script syntax is stable, official, and
 * well-documented, so `ShellBackedMetasploitExecutor` asserts it directly.
 * The Social-Engineer Toolkit's non-interactive automation interface
 * (entrypoint name, config-file format, flags) varies across forks/versions
 * and is not something this implementation can verify — no environment this
 * was built in has a real `setoolkit` installation. Rather than assert a
 * specific invocation as fact, [invocationBuilder] is an explicit,
 * caller-supplied configuration point: [defaultInvocationBuilder] is an
 * honestly-labeled, unverified best-effort default (passes the generated
 * config file as the sole positional argument) that any real deployment
 * should override once the installed SET version's actual automation
 * interface is confirmed. What IS real and tested here: structured input
 * validation ([SetCommand]'s own injection-safety checks), writing that
 * validated data to a real config file, security gating
 * ([SetTool]/`core-security.SecureToolExecutor`), and real shell dispatch —
 * only the exact flags handed to `setoolkit` itself are left unopinionated.
 *
 * **IMPLEMENTED — NOT RUNTIME VERIFIED.**
 */
class ProcessBackedSetExecutor(
    private val shellExecutor: ShellExecutor,
    private val executablePath: String = "setoolkit",
    private val invocationBuilder: (configFile: Path) -> List<String> = ::defaultInvocationBuilder,
) : SetExecutor {
    override fun isAvailable(): Boolean = true

    override fun execute(command: SetCommand, isCancelled: () -> Boolean): SetExecutionResult {
        val configFile = try {
            writeAutomationConfig(command)
        } catch (e: java.io.IOException) {
            return SetExecutionResult.Failure("Failed to write SET automation config: ${e.message}", e)
        }

        val shellCommand = ShellCommand(
            executable = executablePath,
            args = invocationBuilder(configFile),
            timeoutMillis = command.timeoutMillis,
        )

        return when (val result = shellExecutor.execute(shellCommand, isCancelled)) {
            is ShellExecutionResult.Success ->
                SetExecutionResult.Success(result.exitCode, result.stdout, result.stderr, result.durationMillis)
            is ShellExecutionResult.Failure -> SetExecutionResult.Failure(result.reason, result.cause)
        }
    }

    private fun writeAutomationConfig(command: SetCommand): Path {
        val file = Files.createTempFile("droidcommand-set-", ".cfg")
        val lines = buildList {
            add("ATTACK_VECTOR=${command.attackVector.name}")
            command.target.host?.let { add("TARGET_HOST=$it") }
            command.target.emailAddress?.let { add("TARGET_EMAIL=$it") }
            command.payload?.let { add("PAYLOAD=$it") }
            command.options.forEach { (key, value) -> add("$key=$value") }
        }
        Files.write(file, lines)
        return file
    }

    companion object {
        /**
         * [Guessing] — not confirmed against a real SET installation.
         * Override via the constructor's [invocationBuilder] parameter once
         * the actual installed version's automation flags are known.
         */
        fun defaultInvocationBuilder(configFile: Path): List<String> = listOf(configFile.toString())
    }
}
