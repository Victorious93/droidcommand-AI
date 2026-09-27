package ai.droidcommand.termux

import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger
import ai.droidcommand.root.RootCommand
import ai.droidcommand.root.RootExecutionResult
import ai.droidcommand.root.RootExecutor
import java.io.IOException
import java.io.StringReader
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

private sealed class LocalProcessResult {
    data class Ran(val exitCode: Int, val stdout: String, val stderr: String) : LocalProcessResult()
    data class FailedToStart(val reason: String) : LocalProcessResult()
    data object TimedOut : LocalProcessResult()
}

/**
 * A real [TermuxExecutor] reaching Termux's `RUN_COMMAND` service over adb (dispatched as root, see
 * below) — the "PC driving a connected phone over USB debugging" topology `core-root.AdbRootExecutor` already runtime-verified
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
 * **Dispatch runs as root, and the device's Termux must opt in (2026-09-26 correction).** The first
 * version of this class dispatched through a plain `adb shell am startservice`. Checked against
 * Termux's own source (VictorSuite's copy of it) and its RUN_COMMAND wiki page, that could not have
 * worked. `RunCommandService` is guarded by `com.termux.permission.RUN_COMMAND`, a `dangerous`
 * permission that only an app which requests it can be granted, and the adb shell user cannot hold
 * it. The service also refuses every command unless `allow-external-apps=true` is set in the first
 * existing file of `~/.termux/termux.properties` and `~/.config/termux/termux.properties`. On
 * refusal it only posts a notification, which this class would otherwise have seen as a silent poll
 * timeout. So [execute] now dispatches `am start-foreground-service` through the same
 * [rootExecutor] it already required. `RunCommandService` calls `startForeground` in `onCreate`,
 * which is the contract Android 8+ expects for that verb. [execute] also checks [isExternalAppsAllowed]
 * before dispatching. Both changes are still NOT RUNTIME VERIFIED.
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
) : TermuxExecutor, InstalledTerminalProbe {
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

    override fun isTermuxPackageInstalled(): Boolean = isTermuxInstalled()

    /**
     * The installed app's `versionName`, read from `adb shell dumpsys package` (no root, no access to
     * the app's data). Null when it isn't installed or the output has no `versionName=` line.
     */
    override fun termuxPackageVersionName(): String? {
        val result = runLocal(adbArgv() + listOf("shell", "dumpsys", "package", termuxPackage), dispatchTimeoutMillis)
        if (result !is LocalProcessResult.Ran || result.exitCode != 0) return null
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("versionName=") }
            ?.removePrefix("versionName=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Whether the device's Termux accepts external commands at all. It reads the first existing
     * properties file, in the order Termux itself uses, and parses it with [Properties], the same
     * parser Termux uses. The value `true` is compared case-insensitively, as Termux does.
     */
    fun isExternalAppsAllowed(): Boolean {
        val content = termuxPropertiesPaths.firstNotNullOfOrNull { catViaRoot(it) } ?: return false
        val properties = Properties()
        try {
            properties.load(StringReader(content))
        } catch (e: IllegalArgumentException) {
            return false
        }
        return properties.getProperty("allow-external-apps")?.trim()?.equals("true", ignoreCase = true) == true
    }

    private val termuxPropertiesPaths = listOf(
        "$termuxHomeDir/.termux/termux.properties",
        "$termuxHomeDir/.config/termux/termux.properties",
    )

    /** Every precondition [execute] needs to both dispatch a command and retrieve its real result. */
    override fun isAvailable(): Boolean =
        isDeviceConnected() && isTermuxInstalled() && rootExecutor.isRootAvailable() && isExternalAppsAllowed()

    override fun execute(command: TermuxCommand, isCancelled: () -> Boolean): TermuxExecutionResult {
        if (!isDeviceConnected()) return TermuxExecutionResult.Failure("No authorized adb device connected")
        if (!isTermuxInstalled()) return TermuxExecutionResult.Failure("Termux ($termuxPackage) is not installed on the connected device")
        if (!rootExecutor.isRootAvailable()) {
            return TermuxExecutionResult.Failure(
                "Root is unavailable on the connected device: AdbTermuxExecutor needs it to read Termux's " +
                    "command output back (dispatching the command itself does not require root — see class doc)",
            )
        }

        if (!isExternalAppsAllowed()) {
            return TermuxExecutionResult.Failure(
                "Termux on the connected device does not accept external commands: set " +
                    "'allow-external-apps=true' in ~/.termux/termux.properties, then run 'termux-reload-settings'",
            )
        }

        val startedAt = System.currentTimeMillis()
        val markerDir = "$termuxHomeDir/.droidcommand"
        val basename = "$markerDir/${UUID.randomUUID()}"
        val outFile = "$basename.out"
        val errFile = "$basename.err"
        val exitFile = "$basename.exit"

        val wrapped = wrapCommand(command, markerDir, outFile, errFile, exitFile)
        dispatchFailureReason(dispatchRunCommand(wrapped))?.let {
            return TermuxExecutionResult.Failure("Failed to dispatch Termux RUN_COMMAND: $it")
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
     * Runs `am start-foreground-service ...` as root through [rootExecutor]. Only two encoding layers
     * are left here: (1) [wrapped] is already [shellQuote]d internally by [wrapCommand]; (2) `am`'s
     * `--esa` extra takes a comma-separated string array, so a literal comma or backslash inside an
     * element is backslash-escaped ([encodeRunCommandArguments]). Quoting each argv element for the
     * remote shell is [rootExecutor]'s job, for example `AdbRootExecutor`'s double `shellQuote`.
     */
    private fun dispatchRunCommand(wrapped: String): RootExecutionResult = rootExecutor.execute(
        RootCommand(
            executable = "am",
            args = listOf(
                "start-foreground-service", "--user", "0",
                "-n", "$termuxPackage/com.termux.app.RunCommandService",
                "-a", "com.termux.RUN_COMMAND",
                "--es", "com.termux.RUN_COMMAND_PATH", bashExecutable,
                "--esa", "com.termux.RUN_COMMAND_ARGUMENTS", encodeRunCommandArguments(listOf("-c", wrapped)),
                "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "true",
            ),
            timeoutMillis = dispatchTimeoutMillis,
        ),
    )

    /**
     * `null` when the dispatch was accepted. `am` reports some refusals (a missing component, a
     * permission denial) by printing an `Error:` line while still exiting 0, so both the exit code
     * and the output are checked.
     */
    private fun dispatchFailureReason(result: RootExecutionResult): String? = when (result) {
        is RootExecutionResult.Failure -> result.reason
        is RootExecutionResult.Success -> {
            val output = (result.stdout + "\n" + result.stderr).trim()
            when {
                result.exitCode != 0 -> "'am start-foreground-service' exited ${result.exitCode}: $output"
                output.lines().any { it.trimStart().startsWith("Error") } -> "'am start-foreground-service' reported: $output"
                else -> null
            }
        }
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
