package ai.droidcommand.root

import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The outcome of spawning a real [ProcessBuilder] process. Distinguishes
 * "the executable doesn't exist / couldn't be started" ([FailedToStart])
 * from "it started and ran" ([Ran], whatever its exit code) — that
 * distinction is exactly the presence signal [MagiskProvider] needs and a
 * caught generic exception would blur.
 */
internal sealed class ProcessRunResult {
    data class Ran(val exitCode: Int, val stdout: String, val stderr: String) : ProcessRunResult()
    data class FailedToStart(val reason: String) : ProcessRunResult()
    data object TimedOut : ProcessRunResult()
    data object Cancelled : ProcessRunResult()
}

/**
 * Real subprocess execution shared by [MagiskProvider]'s detection probes
 * and its actual privileged-command execution — the same
 * poll-with-timeout-and-cancellation, drain-stdout/stderr-on-separate-threads
 * pattern `core-shell.ProcessBuilderShellExecutor` already established and
 * is real-subprocess-tested against, applied here rather than duplicated
 * with subtle differences.
 */
internal fun runProcess(argv: List<String>, timeoutMillis: Long, isCancelled: () -> Boolean = { false }): ProcessRunResult {
    val process = try {
        ProcessBuilder(argv).start()
    } catch (e: IOException) {
        return ProcessRunResult.FailedToStart(e.message ?: "process failed to start")
    }

    val stdout = StringBuilder()
    val stderr = StringBuilder()
    val stdoutThread = Thread { process.inputStream.bufferedReader().forEachLine { stdout.append(it).append('\n') } }
        .apply {
            isDaemon = true
            start()
        }
    val stderrThread = Thread { process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }
        .apply {
            isDaemon = true
            start()
        }

    val startedAt = System.currentTimeMillis()
    while (!process.waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)) {
        if (isCancelled()) {
            process.destroyForcibly()
            stdoutThread.join(JOIN_TIMEOUT_MILLIS)
            stderrThread.join(JOIN_TIMEOUT_MILLIS)
            return ProcessRunResult.Cancelled
        }
        if (System.currentTimeMillis() - startedAt > timeoutMillis) {
            process.destroyForcibly()
            stdoutThread.join(JOIN_TIMEOUT_MILLIS)
            stderrThread.join(JOIN_TIMEOUT_MILLIS)
            return ProcessRunResult.TimedOut
        }
    }
    stdoutThread.join(JOIN_TIMEOUT_MILLIS)
    stderrThread.join(JOIN_TIMEOUT_MILLIS)

    return ProcessRunResult.Ran(process.exitValue(), stdout.toString(), stderr.toString())
}

private const val POLL_INTERVAL_MILLIS = 50L
private const val JOIN_TIMEOUT_MILLIS = 2_000L

/**
 * POSIX single-quote shell escaping: wraps [arg] in single quotes and
 * replaces any embedded `'` with `'\''` (close quote, escaped literal
 * quote, reopen quote) — the standard safe way to pass an arbitrary,
 * untrusted string through a shell as exactly one argument. Required
 * because `su -c` takes one command string to hand to a shell, unlike
 * every other executor in this codebase (`ProcessBuilderShellExecutor`,
 * `PolicyEnforcingRootExecutor`'s own delegate) which pass an argv vector
 * directly to `execve` with no shell involved at all — this is the one
 * place in the codebase that genuinely needs shell-string construction,
 * and [MagiskProviderTest] proves a crafted argument (`;`, backticks,
 * `$()`, embedded quotes) is neutralized rather than interpreted.
 */
internal fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"
