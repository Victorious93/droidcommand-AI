package ai.droidcommand.flash

import ai.droidcommand.rootforge.SshCommand
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** One request exchange with a flash node; the node may answer with several frames. */
interface FlashTransport {
    /**
     * Sends [requestLine] and feeds every non-blank stdout line to [onLine] as it arrives. Stops
     * reading when [onLine] returns true (final frame seen) or the process ends.
     */
    fun exchange(requestLine: String, timeoutMs: Long, onLine: (String) -> Boolean): Result<Unit>
}

class FlashTransportException(message: String) : RuntimeException(message)

/**
 * Runs [argv], writes the request line, closes stdin, then streams stdout lines. Bounded: a line
 * longer than [maxLineBytes] or more than [maxLines] lines fails the exchange. stderr is kept only
 * as a short hint and never parsed as protocol.
 */
open class ProcessFlashTransport(
    private val argv: List<String>,
    private val maxLineBytes: Int = 1_048_576,
    private val maxLines: Int = 1_000_000,
) : FlashTransport {
    override fun exchange(requestLine: String, timeoutMs: Long, onLine: (String) -> Boolean): Result<Unit> {
        val process = try {
            ProcessBuilder(argv).start()
        } catch (e: java.io.IOException) {
            return Result.failure(FlashTransportException("cannot start ${argv.first()}: ${e.message}"))
        }
        val err = ByteArrayOutputStream()
        Thread {
            process.errorStream.use { s ->
                val b = ByteArray(4096)
                while (true) {
                    val n = s.read(b)
                    if (n < 0) break
                    if (err.size() < 4096) err.write(b, 0, minOf(n, 4096 - err.size()))
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            process.outputStream.use { it.write((requestLine + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: java.io.IOException) {
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        val watchdog = Thread {
            try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            } catch (_: InterruptedException) {
            }
        }.apply {
            isDaemon = true
            start()
        }
        var finished = false
        var failure: String? = null
        var count = 0
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { r ->
            while (true) {
                val line = try {
                    r.readLine()
                } catch (_: java.io.IOException) {
                    null
                } ?: break
                if (line.isBlank()) continue
                if (line.length > maxLineBytes) {
                    failure = "response frame exceeded $maxLineBytes bytes"
                    break
                }
                if (++count > maxLines) {
                    failure = "too many response frames"
                    break
                }
                if (onLine(line)) {
                    finished = true
                    break
                }
            }
        }
        process.destroy()
        watchdog.interrupt()
        failure?.let { return Result.failure(FlashTransportException(it)) }
        if (finished) return Result.success(Unit)
        if (System.currentTimeMillis() >= deadline) return Result.failure(FlashTransportException("timed out after $timeoutMs ms"))
        val hint = err.toString(Charsets.UTF_8).trim().take(300)
        return Result.failure(FlashTransportException("no final response" + if (hint.isNotEmpty()) ": $hint" else ""))
    }
}

/**
 * OpenSSH transport with the node's host key pinned into a private per-exchange known_hosts,
 * built by core-rootforge's [SshCommand] (StrictHostKeyChecking=yes, no agent/forwarding/password,
 * host after `--`, no remote command — the node's authorized key forces `victorious-flash-bridge serve`).
 * JVM/CLI only; Android has no system ssh.
 */
class SshFlashTransport(
    private val node: FlashNodeConfig,
    private val sshExecutable: String = "ssh",
) : FlashTransport {
    override fun exchange(requestLine: String, timeoutMs: Long, onLine: (String) -> Boolean): Result<Unit> {
        val dir = try {
            Files.createTempDirectory("flash-ssh-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (e: java.io.IOException) {
            return Result.failure(FlashTransportException("cannot create private temp dir: ${e.message}"))
        }
        try {
            val knownHosts = dir.resolve("known_hosts")
            Files.writeString(knownHosts, SshCommand.knownHostsLine(node.host, node.port, node.hostKey) + "\n")
            val argv = SshCommand.build(node.sshTarget, knownHosts, sshExecutable, connectTimeoutSeconds = (timeoutMs / 1000).coerceIn(1, 30).toInt())
            return ProcessFlashTransport(argv).exchange(requestLine, timeoutMs, onLine)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
