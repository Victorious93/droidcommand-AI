package ai.droidcommand.setoolkit

import ai.droidcommand.termux.KaliRunCommandEnvelope
import ai.droidcommand.termux.ProotDistroKaliEnvelope
import ai.droidcommand.termux.TermuxExecutionResult
import ai.droidcommand.termux.TermuxExecutor

/**
 * [SetExecutor] that drives `setoolkit` **inside VictorSuite's Kali proot** via [TermuxExecutor] /
 * `run_termux_command` / `com.termux.RUN_COMMAND`, the same real mechanism [ai.droidcommand.metasploit.TermuxMetasploitExecutor]
 * uses for `msfconsole`.
 *
 * SET has no official scripting flag. What this executor relies on is a plainer, version-independent fact: SET is a
 * Python CLI that reads each menu choice via `input()`, so a sequence of lines piped into its stdin substitutes for
 * an operator typing them. [stdinSequence] builds that sequence: [SetMenuPath]'s now-verified main-menu navigation
 * (skippable with [useMenuPath] = false), then [SetCommand.menuAnswers] verbatim — see [SetMenuPath]'s own doc
 * comment for exactly what was verified and the one vector ([SetAttackVector.SMS_SPOOFING]) it refuses to guess a
 * digit for. Everything after the navigation digits is exactly what the caller supplied, nothing inferred.
 * [execute] never sends anything the caller has not already seen: callers build
 * the review text shown before every run (this app's confirmation dialog, same as every other tool) from this same
 * [stdinSequence] function, not from a separate rendering of the command.
 *
 * **IMPLEMENTED — NOT RUNTIME VERIFIED.** No session that built this has run `setoolkit` on a device. A successful
 * [TermuxExecutionResult.Success] from a fake [TermuxExecutor] in tests is not a live SET round-trip, and a session
 * that stalls on a prompt [stdinSequence] did not anticipate will read as a timeout, not a clean failure.
 */
class TermuxSetExecutor(
    private val termux: TermuxExecutor,
    private val envelope: KaliRunCommandEnvelope = ProotDistroKaliEnvelope(),
) : SetExecutor {
    override fun isAvailable(): Boolean = termux.isAvailable()

    override fun execute(command: SetCommand, isCancelled: () -> Boolean): SetExecutionResult {
        if (!isAvailable()) {
            return SetExecutionResult.Failure(
                "Cannot run SET attack vector '${command.attackVector}': VictorSuite RUN_COMMAND is not available",
            )
        }
        val lines = try {
            stdinSequence(command)
        } catch (e: UnsupportedSetVectorException) {
            return SetExecutionResult.Failure(e.message ?: "unsupported SET attack vector '${e.vector}'", e)
        }
        val termuxCommand = envelope.setoolkitStdinSequence(lines, command.timeoutMillis)
        return when (val result = termux.execute(termuxCommand, isCancelled)) {
            is TermuxExecutionResult.Success ->
                SetExecutionResult.Success(result.exitCode, result.stdout, result.stderr, result.durationMillis)
            is TermuxExecutionResult.Failure -> SetExecutionResult.Failure(result.reason, result.cause)
        }
    }

    companion object {
        /**
         * The exact, ordered lines this executor will type into `setoolkit`'s stdin for [command] — the same thing a
         * caller should show an operator before running it. Honors [SetCommand.useMenuPath].
         */
        fun stdinSequence(command: SetCommand): List<String> =
            (if (command.useMenuPath) SetMenuPath.navigationDigits(command.attackVector) else emptyList()) + command.menuAnswers
    }
}
