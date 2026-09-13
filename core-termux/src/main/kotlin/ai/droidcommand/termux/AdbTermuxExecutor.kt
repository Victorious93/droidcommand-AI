package ai.droidcommand.termux

import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger
import ai.droidcommand.root.RootCommand
import ai.droidcommand.root.RootExecutionResult
import ai.droidcommand.root.RootExecutor
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

private sealed class LocalProcessResult {
    data class Ran(val exitCode: Int, val stdout: String, val stderr: String) : LocalProcessResult()
    data class FailedToStart(val reason: String) : LocalProcessResult()
    data object TimedOut : LocalProcessResult()
}

/**
 * A real [TermuxExecutor] reaching Termux over `adb shell am startservice ...` — the "PC driving a
 * connected phone over USB debugging" topology `core-root.AdbRootExecutor` already runtime-verified
 * against a real device (`docs/AUDIT_2026-09-05.md`'s "AdbRootExecutor" addendum), applied here to a
 * different Android component.
 *
 * **Honesty label, matching `core-root.MagiskProvider`'s own precedent before `AdbRootExecutor`
 * verified its adjacent topology: this class is `IMPLEMENTED — NOT RUNTIME VERIFIED`.** The
 * `com.termux.RUN_COMMAND` intent (component `com.termux/com.termux.app.RunCommandService`, action
 * `com.termux.RUN_COMMAND`, extras `com.termux.RUN_COMMAND_PATH`/`_ARGUMENTS`/`_WORKDIR`/`_BACKGROUND`)
 * is Termux's own publicly documented external-command API (the same one Termux:Tasker/Termux:Widget
 * use) reproduced here from training knowledge, not from a live lookup — no adb binary or connected
 * device exists in the environment this class was written in (`which adb` failed outright), so none of
 * this has been exercised against a real Termux install. A future session with real hardware must
 * verify it — start with [isDeviceConnected]/[isTermuxInstalled] (passive, side-effect-free) before
 * ever calling [execute].
 *
 * **A real, deliberate architectural tradeoff, named rather than hidden:** dispatching a `RUN_COMMAND`
 * intent needs no root at all — but Android's own app-sandboxing means a plain, non-root `adb shell`
 * cannot read another app's private data directory (Termux's home lives under
 * `/data/data/com.termux/files/home`, private to Termux's own UID). Since [execute] must return real
 * stdout/stderr/an exit code, not merely fire the command and hope, this class asks the *injected*
 * [rootExecutor] to `cat`/`rm` the redirected-output marker files back — composing with the root access
 * this same environment already has verified (`AdbRootExecutor`), rather than inventing a second,
 * unverified access path. This means [isAvailable] is honestly `false` whenever root isn't available,
 * even though the Termux command itself would need no root to run — a real limitation of *this backend*,
 * not of the [TermuxExecutor] abstraction (see that interface's own doc comment). A future
 * improvement that removes the root dependency entirely — e.g. writing output to shared/external
 * storage instead of Termux's private home directory — is named here as a real follow-up, not silently
 * assumed to be the only possible design.
 *
 * **No cancellation of an in-flight remote command:** [execute]'s `isCancelled` stops this class from
 * *waiting* on a result, but `RUN_COMMAND` gives no channel to kill the command Termux is already
 * running — the same class of limitation `core-root.AdbRootExecutor` does not have (it can
 * `destroyForcibly()` its own locally-spawned process) but this indirect dispatch mechanism cannot avoid.
 *
 * Every constructor parameter is injectable so tests can point this at real, controlled fixtures (a
 * scripted `adb` shell script, a fake [RootExecutor] backed by a real local temp directory) rather than
 * mocking process execution, mirroring [ai.droidcommand.root.AdbRootExecutor]'s own precedent.
 */
class AdbTermuxExecutor(
    private val rootExecutor: RootExecutor,
    private val adbExecutable: String = "adb",
    private val serial: String? = null,
    private val termuxPackage: String = "com.termux",
    private val termuxHomeDir: String = "/data/data/com.termux/files/home",
    private val bashExecutable: String = "/data/data/com.termux/files/usr/bin/bash",
    private val pollIntervalMillis: Long = 300,
    private val dispatchTimeoutMillis: Long = 10_000,
    private val logger: Logger = NoOpLogger,
) : TermuxExecutor {
    /** Presence only — no RUN_COMMAND dispatch, no root call. Mirrors [ai.droidcommand.root.AdbRootExecutor.isDeviceConnected]. */
    fun isDeviceConnected(): Boolean {
        val result = runLocal(adbArgv() + listOf("get-state"), dispatchTimeoutMillis)
        return result is LocalProcessResult.Ran && result.exitCode == 0 && result.stdout.trim() == "device"
    }

    /** Passive package-presence check over a plain, non-root `adb shell` — never touches Termux's private data. */
    fun isTermuxInstalled(): Boolean {
        val result = runLocal(adbArgv() + listOf("shell", "pm", "list", "packages", termuxPackage), dispatchTimeoutMillis)
        return result is LocalProcessResult.Ran && result.exitCode == 0 && result.stdout.contains("package:$termuxPackage")
    }

    /** Every precondition [execute] needs to both dispatch a command and retrieve its real result. */
    override fun isAvailable(): Boolean = isDeviceConnected() && isTermuxInstalled() && rootExecutor.isRootAvailable()

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        if (!isDeviceConnected()) return TermuxExecutionResult.Failure("No authorized adb device connected")
        if (!isTermuxInstalled()) return TermuxExecutionResult.Failure("Termux ($termuxPackage) is not installed on the connected device")
        if (!rootExecutor.isRootAvailable()) {
            return TermuxExecutionResult.Failure(
                "Root is unavailable on the connected device: AdbTermuxExecutor needs it to read Termux's " +
                    "command output back (dispatching the command itself does not require root — see class doc)",
            )
        }

        val startedAt = System.currentTimeMillis()
        val markerDir = "$termuxHomeDir/.droidcommand"
        val basename = "$markerDir/${UUID.randomUUID()}"
        val outFile = "$basename.out"
        val errFile = "$basename.err"
        val exitFile = "$basename.exit"

        val wrapped = wrapCommand(command, markerDir, outFile, errFile, exitFile)
        val dispatch = dispatchRunCommand(wrapped)
        if (dispatch !is LocalProcessResult.Ran || dispatch.exitCode != 0) {
            val reason = when (dispatch) {
                is LocalProcessResult.FailedToStart -> "Failed to start '$adbExecutable': ${dispatch.reason}"
                LocalProcessResult.TimedOut -> "Dispatching the RUN_COMMAND intent timed out after ${dispatchTimeoutMillis}ms"
                is LocalProcessResult.Ran -> "'adb shell am startservice' exited ${dispatch.exitCode}: ${dispatch.stderr.ifBlank { dispatch.stdout }}"
            }
            return TermuxExecutionResult.Failure("Failed to dispatch Termux RUN_COMMAND: $reason")
        }
        logger.info("termux_run_command_dispatched", mapOf("basename" to basename))

        val deadline = startedAt + command.timeoutMillis
        var exitContent: String?
        while (true) {
            if (isCancelled()) return TermuxExecutionResult.Failure("Command cancelled while waiting for Termux result")
            exitContent = catViaRoot(exitFile)
            if (!exitContent.isNullOrBlank()) break
            if (System.currentTimeMillis() > deadline) {
                return TermuxExecutionResult.Failure("Command timed out after ${command.timeoutMillis}ms waiting for Termux to finish")
            }
            Thread.sleep(pollIntervalMillis)
        }

        val exitCode = exitContent.trim().toIntOrNull()
            ?: return TermuxExecutionResult.Failure("Could not parse Termux exit code from '${exitContent.trim()}'")
        val stdout = catViaRoot(outFile) ?: ""
        val stderr = catViaRoot(errFile) ?: ""
        cleanup(basename)

        return TermuxExecutionResult.Success(
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr,
            durationMillis = System.currentTimeMillis() - startedAt,
        )
    }

    private fun adbArgv(): List<String> = if (serial != null) listOf(adbExecutable, "-s", serial) else listOf(adbExecutable)

    /**
     * `cd <dir> && VAR=value ... exe args > out 2> err; echo $? > exit`, reusing the exact
     * cd/env-prefix + [shellQuote] construction `AdbRootExecutor.execute` already establishes, plus a
     * leading `mkdir -p` so the marker directory exists on first use.
     */
    private fun wrapCommand(command: TermuxCommand, markerDir: String, outFile: String, errFile: String, exitFile: String): String {
        val innerCommand = (listOf(command.executable) + command.args).joinToString(" ") { shellQuote(it) }
        val cdPrefix = command.workingDirectory?.let { "cd ${shellQuote(it)} && " } ?: ""
        val envPrefix = command.environment.entries.joinToString("") { (key, value) -> "$key=${shellQuote(value)} " }
        val mkdirPrefix = "mkdir -p ${shellQuote(markerDir)} && "
        return "$mkdirPrefix$cdPrefix$envPrefix$innerCommand > ${shellQuote(outFile)} 2> ${shellQuote(errFile)}; echo \$? > ${shellQuote(exitFile)}"
    }

    /**
     * Builds and runs the `adb shell am startservice ...` dispatch line. Three layers of encoding are
     * genuinely required here, each named so a future fix knows exactly which one to touch: (1) [wrapped]
     * is already [shellQuote]d internally by [wrapCommand]; (2) `am`'s `--esa` extra takes a
     * comma-separated string array, so a literal comma inside `-c`/[wrapped] must be backslash-escaped
     * per element ([encodeRunCommandArguments]); (3) the whole resulting `--esa` value is itself
     * [shellQuote]d again before being appended to the local `adb` argv, because real `adb shell`
     * flattens every trailing local argument into one space-joined line before the device's shell parses
     * it (the same reason `AdbRootExecutor.execute` double-quotes) — without this layer, an `am` argument
     * containing spaces or shell metacharacters would be mis-split by the remote shell.
     */
    private fun dispatchRunCommand(wrapped: String): LocalProcessResult {
        val argumentsValue = encodeRunCommandArguments(listOf("-c", wrapped))
        val argv = adbArgv() + listOf(
            "shell", "am", "startservice", "--user", "0",
            "-n", "$termuxPackage/com.termux.app.RunCommandService",
            "-a", "com.termux.RUN_COMMAND",
            "--es", "com.termux.RUN_COMMAND_PATH", shellQuote(bashExecutable),
            "--esa", "com.termux.RUN_COMMAND_ARGUMENTS", shellQuote(argumentsValue),
            "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "true",
        )
        return runLocal(argv, dispatchTimeoutMillis)
    }

    private fun catViaRoot(path: String): String? {
        val result = rootExecutor.execute(RootCommand(executable = "cat", args = listOf(path)))
        return if (result is RootExecutionResult.Success && result.exitCode == 0) result.stdout else null
    }

    /** Best-effort: a cleanup failure is logged, never fails the overall result. */
    private fun cleanup(basename: String) {
        val result = rootExecutor.execute(RootCommand(executable = "rm", args = listOf("-f", "$basename.out", "$basename.err", "$basename.exit")))
        if (result is RootExecutionResult.Failure) {
            logger.warn("termux_marker_cleanup_failed", mapOf("basename" to basename, "reason" to result.reason))
        }
    }

    private fun runLocal(argv: List<String>, timeoutMillis: Long): LocalProcessResult {
        val process = try {
            ProcessBuilder(argv).start()
        } catch (e: IOException) {
            return LocalProcessResult.FailedToStart(e.message ?: "process failed to start")
        }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return LocalProcessResult.TimedOut
        }
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return LocalProcessResult.Ran(process.exitValue(), stdout, stderr)
    }
}

/** POSIX single-quote shell escaping, identical to `ai.droidcommand.root`'s internal helper of the same name (not visible cross-module). */
internal fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

/** `am`'s `--esa` comma-separated string-array encoding: a literal backslash or comma in an element is backslash-escaped. */
internal fun encodeRunCommandArguments(elements: List<String>): String =
    elements.joinToString(",") { it.replace("\\", "\\\\").replace(",", "\\,") }
