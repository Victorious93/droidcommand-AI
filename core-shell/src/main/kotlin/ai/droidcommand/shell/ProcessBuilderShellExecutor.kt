package ai.droidcommand.shell

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Real [ShellExecutor] backed by [ProcessBuilder]. [policy] is enforced
 * before a process is ever spawned: an executable outside
 * [ShellSecurityPolicy.allowedExecutables], or a working-directory
 * override that isn't inside [ShellSecurityPolicy.allowedWorkingDirectories],
 * is rejected with no side effect at all — no process starts, nothing is
 * touched on disk. stdout/stderr are drained on separate threads
 * concurrently with waiting on the process, which avoids the classic
 * `ProcessBuilder` deadlock where a process blocks trying to write to a
 * full pipe that nothing is reading.
 */
class ProcessBuilderShellExecutor(private val policy: ShellSecurityPolicy) : ShellExecutor {
    override fun execute(command: ShellCommand, isCancelled: () -> Boolean): ShellExecutionResult {
        val workingDirectory = when (val authorization = authorize(command)) {
            is Authorization.Denied -> return ShellExecutionResult.Failure(authorization.reason)
            is Authorization.Allowed -> authorization.workingDirectory
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        return when (
            val outcome = runProcess(
                command,
                workingDirectory,
                isCancelled,
                stdoutDrain = { drainText(it, stdout, command.maxOutputBytes) },
                stderrDrain = { drainText(it, stderr, command.maxOutputBytes) },
            )
        ) {
            is ProcessOutcome.Failed -> ShellExecutionResult.Failure(outcome.reason, outcome.cause)
            is ProcessOutcome.Completed ->
                ShellExecutionResult.Success(outcome.exitCode, stdout.toString(), stderr.toString(), outcome.durationMillis)
        }
    }

    /** Real [ShellBinaryExecutionResult] implementation — see [ShellExecutor.executeBinary]'s own doc comment for why this exists. */
    override fun executeBinary(command: ShellCommand, isCancelled: () -> Boolean): ShellBinaryExecutionResult {
        val workingDirectory = when (val authorization = authorize(command)) {
            is Authorization.Denied -> return ShellBinaryExecutionResult.Failure(authorization.reason)
            is Authorization.Allowed -> authorization.workingDirectory
        }

        val stdout = ByteArrayOutputStream()
        val stderr = StringBuilder()
        return when (
            val outcome = runProcess(
                command,
                workingDirectory,
                isCancelled,
                stdoutDrain = { drainBinary(it, stdout, command.maxOutputBytes) },
                stderrDrain = { drainText(it, stderr, command.maxOutputBytes) },
            )
        ) {
            is ProcessOutcome.Failed -> ShellBinaryExecutionResult.Failure(outcome.reason, outcome.cause)
            is ProcessOutcome.Completed ->
                ShellBinaryExecutionResult.Success(outcome.exitCode, stdout.toByteArray(), stderr.toString(), outcome.durationMillis)
        }
    }

    private sealed class Authorization {
        data class Allowed(val workingDirectory: File?) : Authorization()
        data class Denied(val reason: String) : Authorization()
    }

    private fun authorize(command: ShellCommand): Authorization {
        if (command.executable !in policy.allowedExecutables) {
            return Authorization.Denied("Executable '${command.executable}' is not in the allowed executable list")
        }

        if (command.workingDirectory == null) return Authorization.Allowed(null)

        if (policy.allowedWorkingDirectories.isEmpty()) {
            return Authorization.Denied("No working directory is authorized; cannot run in '${command.workingDirectory}'")
        }
        val candidate = File(command.workingDirectory).canonicalFile
        val authorized = policy.allowedWorkingDirectories.any { allowed ->
            candidate.path.startsWith(File(allowed).canonicalFile.path)
        }
        if (!authorized) {
            return Authorization.Denied("Working directory '${command.workingDirectory}' is not authorized")
        }
        return Authorization.Allowed(candidate)
    }

    private sealed class ProcessOutcome {
        data class Completed(val exitCode: Int, val durationMillis: Long) : ProcessOutcome()
        data class Failed(val reason: String, val cause: Throwable? = null) : ProcessOutcome()
    }

    /**
     * Spawns the real process and waits for it, delegating exactly how each stream is captured to
     * [stdoutDrain]/[stderrDrain] — shared between [execute] (text) and [executeBinary] (raw bytes) so
     * the trickier cancellation/timeout/process-lifecycle logic exists in exactly one place.
     */
    private fun runProcess(
        command: ShellCommand,
        workingDirectory: File?,
        isCancelled: () -> Boolean,
        stdoutDrain: (InputStream) -> Unit,
        stderrDrain: (InputStream) -> Unit,
    ): ProcessOutcome {
        val builder = ProcessBuilder(listOf(command.executable) + command.args)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(command.environment)

        val startedAt = System.currentTimeMillis()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ProcessOutcome.Failed("Failed to start '${command.executable}': ${e.message}", e)
        }

        val stdoutThread = Thread { stdoutDrain(process.inputStream) }.apply {
            isDaemon = true
            start()
        }
        val stderrThread = Thread { stderrDrain(process.errorStream) }.apply {
            isDaemon = true
            start()
        }

        while (!process.waitFor(POLL_INTERVAL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                process.destroyForcibly()
                stdoutThread.join(JOIN_TIMEOUT_MILLIS)
                stderrThread.join(JOIN_TIMEOUT_MILLIS)
                return ProcessOutcome.Failed("Command cancelled")
            }
            if (System.currentTimeMillis() - startedAt > command.timeoutMillis) {
                process.destroyForcibly()
                stdoutThread.join(JOIN_TIMEOUT_MILLIS)
                stderrThread.join(JOIN_TIMEOUT_MILLIS)
                return ProcessOutcome.Failed("Command timed out after ${command.timeoutMillis}ms")
            }
        }

        stdoutThread.join(JOIN_TIMEOUT_MILLIS)
        stderrThread.join(JOIN_TIMEOUT_MILLIS)

        return ProcessOutcome.Completed(process.exitValue(), System.currentTimeMillis() - startedAt)
    }

    private fun drainText(input: InputStream, into: StringBuilder, maxBytes: Long) {
        input.bufferedReader().forEachLine { line ->
            if (into.length < maxBytes) {
                into.append(line).append('\n')
            }
        }
    }

    /** No charset decode, no line splitting — copies raw bytes exactly as read, capped at [maxBytes]. */
    private fun drainBinary(input: InputStream, into: ByteArrayOutputStream, maxBytes: Long) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (into.size() < maxBytes) {
                val toKeep = minOf(read.toLong(), maxBytes - into.size()).toInt()
                into.write(buffer, 0, toKeep)
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 50L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
