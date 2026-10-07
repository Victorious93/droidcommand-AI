package ai.droidcommand.rootforge

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** One request/response exchange with a RootForge node. */
interface RootForgeTransport {
    /** Sends [requestLine] (no trailing newline) and returns the single response line. */
    fun exchange(requestLine: String, timeoutMs: Long): Result<String>
}

/**
 * Runs [argv] as a child process, writes the request line to its stdin, closes stdin (which ends
 * the bridge's read loop), and returns the first stdout line. Used directly for local contract
 * tests and as the engine under [SshRootForgeTransport]. Output is size-capped; stderr is
 * captured only to a short, truncated hint and is never treated as protocol data.
 */
open class ProcessRootForgeTransport(
    private val argv: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val maxOutputBytes: Int = 1_048_576,
) : RootForgeTransport {
    protected open fun prepare(): Result<AutoCloseable> = Result.success(AutoCloseable { })

    override fun exchange(requestLine: String, timeoutMs: Long): Result<String> {
        val cleanup = prepare().getOrElse { return Result.failure(it) }
        cleanup.use {
            return runProcess(requestLine, timeoutMs)
        }
    }

    private fun runProcess(requestLine: String, timeoutMs: Long): Result<String> {
        val builder = ProcessBuilder(argv)
        builder.environment().putAll(environment)
        val process = try {
            builder.start()
        } catch (e: java.io.IOException) {
            return Result.failure(TransportException("cannot start ${argv.first()}: ${e.message}"))
        }
        val err = ByteArrayOutputStream()
        val errThread = Thread { process.errorStream.copyBounded(err, 4096) }.apply {
            isDaemon = true
            start()
        }
        val out = ByteArrayOutputStream()
        val overflow = java.util.concurrent.atomic.AtomicBoolean(false)
        val outThread = Thread { overflow.set(process.inputStream.copyBounded(out, maxOutputBytes)) }.apply {
            isDaemon = true
            start()
        }
        try {
            process.outputStream.use { it.write((requestLine + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: java.io.IOException) {
            // The process may have exited already (e.g. ssh auth failure); fall through to exit handling.
        }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return Result.failure(TransportException("timed out after $timeoutMs ms"))
        }
        outThread.join(2000)
        errThread.join(2000)
        if (overflow.get()) return Result.failure(TransportException("response exceeded $maxOutputBytes bytes"))
        val line = out.toString(Charsets.UTF_8).lineSequence().firstOrNull { it.isNotBlank() }
        if (line == null) {
            val hint = err.toString(Charsets.UTF_8).trim().take(300)
            return Result.failure(TransportException("no response (exit ${process.exitValue()})" + if (hint.isNotEmpty()) ": $hint" else ""))
        }
        return Result.success(line)
    }

    /** Copies up to [limit] bytes, discarding (never buffering) the rest; returns true if anything was discarded. */
    private fun InputStream.copyBounded(sink: ByteArrayOutputStream, limit: Int): Boolean {
        val buf = ByteArray(8192)
        var overflowed = false
        use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) return overflowed
                val room = limit - sink.size()
                if (n <= room) {
                    sink.write(buf, 0, n)
                } else {
                    if (room > 0) sink.write(buf, 0, room)
                    overflowed = true
                }
            }
        }
    }
}

class TransportException(message: String) : RuntimeException(message)

/**
 * SSH transport using the platform's OpenSSH client. The host key is pinned from
 * [RootForgeNodeConfig.hostKey] into a private, per-exchange known_hosts file with
 * `StrictHostKeyChecking=yes`, the user's/global known_hosts are ignored, and agent/forwarding/
 * password authentication are all off. No remote command is sent: the node's authorized key
 * carries a forced command that starts the bridge.
 *
 * This is the JVM/CLI transport. Android has no system `ssh` binary; an Android build needs a
 * separate [RootForgeTransport] backed by a vetted SSH library — not built here.
 */
class SshRootForgeTransport(
    private val node: RootForgeNodeConfig,
    private val sshExecutable: String = "ssh",
) : RootForgeTransport {
    override fun exchange(requestLine: String, timeoutMs: Long): Result<String> {
        val dir = try {
            Files.createTempDirectory("rootforge-ssh-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } catch (e: java.io.IOException) {
            return Result.failure(TransportException("cannot create private temp dir: ${e.message}"))
        }
        try {
            val knownHosts = dir.resolve("known_hosts")
            Files.writeString(knownHosts, SshCommand.knownHostsLine(node) + "\n")
            val argv = SshCommand.build(node, knownHosts, sshExecutable, connectTimeoutSeconds = (timeoutMs / 1000).coerceIn(1, 30).toInt())
            return ProcessRootForgeTransport(argv).exchange(requestLine, timeoutMs)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

object SshCommand {
    /** `[host]:port key-type base64` — the form OpenSSH uses for non-default ports; also valid for 22. */
    fun knownHostsLine(node: RootForgeNodeConfig): String = "[${node.host}]:${node.port} ${node.hostKey}"

    fun build(node: RootForgeNodeConfig, knownHosts: Path, sshExecutable: String = "ssh", connectTimeoutSeconds: Int = 10): List<String> = listOf(
        sshExecutable,
        "-T",
        "-o", "BatchMode=yes",
        "-o", "StrictHostKeyChecking=yes",
        "-o", "UserKnownHostsFile=$knownHosts",
        "-o", "GlobalKnownHostsFile=/dev/null",
        "-o", "HostKeyAlgorithms=${node.hostKeyType}",
        "-o", "IdentitiesOnly=yes",
        "-o", "IdentityAgent=none",
        "-o", "PasswordAuthentication=no",
        "-o", "KbdInteractiveAuthentication=no",
        "-o", "ClearAllForwardings=yes",
        "-o", "ForwardAgent=no",
        "-o", "ForwardX11=no",
        "-o", "ControlMaster=no",
        "-o", "ConnectTimeout=$connectTimeoutSeconds",
        "-i", node.identityFile,
        "-p", node.port.toString(),
        "-l", node.user,
        "--",
        node.host,
    )
}
