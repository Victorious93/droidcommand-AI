package ai.droidcommand.shell

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
        if (command.executable !in policy.allowedExecutables) {
            return ShellExecutionResult.Failure("Executable '${command.executable}' is not in the allowed executable list")
        }

        var workingDirectory: File? = null
        if (command.workingDirectory != null) {
            if (policy.allowedWorkingDirectories.isEmpty()) {
                return ShellExecutionResult.Failure("No working directory is authorized; cannot run in '${command.workingDirectory}'")
            }
            val candidate = File(command.workingDirectory).canonicalFile
            val authorized = policy.allowedWorkingDirectories.any { allowed ->
                candidate.path.startsWith(File(allowed).canonicalFile.path)
            }
            if (!authorized) {
                return ShellExecutionResult.Failure("Working directory '${command.workingDirectory}' is not authorized")
            }
            workingDirectory = candidate
        }

        val builder = ProcessBuilder(listOf(command.executable) + command.args)
        workingDirectory?.let { builder.directory(it) }
        builder.environment().putAll(command.environment)

        val startedAt = System.currentTimeMillis()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ShellExecutionResult.Failure("Failed to start '${command.executable}': ${e.message}", e)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val stdoutThread = Thread { drain(process.inputStream, stdout, command.maxOutputBytes) }.apply { isDaemon = true; start() }
        val stderrThread = Thread { drain(process.errorStream, stderr, command.maxOutputBytes) }.apply { isDaemon = true; start() }

        while (!process.waitFor(POLL_INTERVAL_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                process.destroyForcibly()
                stdoutThread.join(JOIN_TIMEOUT_MILLIS)
                stderrThread.join(JOIN_TIMEOUT_MILLIS)
                return ShellExecutionResult.Failure("Command cancelled")
            }
            if (System.currentTimeMillis() - startedAt > command.timeoutMillis) {
                process.destroyForcibly()
                stdoutThread.join(JOIN_TIMEOUT_MILLIS)
                stderrThread.join(JOIN_TIMEOUT_MILLIS)
                return ShellExecutionResult.Failure("Command timed out after ${command.timeoutMillis}ms")
            }
        }

        stdoutThread.join(JOIN_TIMEOUT_MILLIS)
        stderrThread.join(JOIN_TIMEOUT_MILLIS)

        return ShellExecutionResult.Success(
            exitCode = process.exitValue(),
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            durationMillis = System.currentTimeMillis() - startedAt,
        )
    }

    private fun drain(input: InputStream, into: StringBuilder, maxBytes: Long) {
        input.bufferedReader().forEachLine { line ->
            if (into.length < maxBytes) {
                into.append(line).append('\n')
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 50L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
