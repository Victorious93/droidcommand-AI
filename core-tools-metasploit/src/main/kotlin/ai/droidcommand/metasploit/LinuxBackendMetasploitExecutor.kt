package ai.droidcommand.metasploit

import ai.droidcommand.remote.RequestCancellation
import ai.droidcommand.securitybackend.JobState
import ai.droidcommand.securitybackend.SecurityBackendClient
import ai.droidcommand.securitybackend.SecurityBackendException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/**
 * [MetasploitExecutor] that runs a module through VictorLinux's `secbackend.py`, over
 * Metasploit's own documented MessagePack RPC on that end (see that repo's `secmsf.py` doc
 * comment) — the Linux-backend counterpart to [TermuxMetasploitExecutor]'s on-device Kali
 * proot path. The two are independent: either works without the other, and this app's
 * owner picks one per job, never a silent fallback from one to the other (see
 * `AppModule`'s backend-selection wiring).
 *
 * **Verified against a real running `secbackend.py` instance backed by a real `msfrpcd`
 * on 2026-11-19** — see VictorLinux's `docs/security-backend.md#full-service-verification`.
 * This Kotlin class's own tests use a fake [ai.droidcommand.remote.HttpTransport] built from
 * byte-for-byte copies of that real session's responses (see
 * `core-security-backend`'s `SecurityBackendClientTest`), not an invented shape — but no
 * session that wired *this specific class* has pointed a real phone/JVM process at a real
 * running `secbackend.py` end to end; that is the concrete remaining gap, not a claim this
 * class makes for itself.
 */
class LinuxBackendMetasploitExecutor(
    private val client: SecurityBackendClient,
    private val pollIntervalMillis: Long = 1_000,
) : MetasploitExecutor {
    override fun isAvailable(): Boolean = runCatching {
        client.capabilities().operations["metasploit.module"]?.available == true
    }.getOrDefault(false)

    override fun execute(command: MetasploitCommand, isCancelled: () -> Boolean): MetasploitExecutionResult {
        if (command.moduleType == MetasploitModuleType.PAYLOAD) {
            return MetasploitExecutionResult.Failure(
                "The Linux backend runs exploit/auxiliary/post modules via 'use <type>/<path>'; " +
                    "generating a standalone payload (msfvenom) is not wired into this executor yet.",
            )
        }
        val cancellation = RequestCancellation()
        val params = buildJsonObject {
            put("module_type", command.moduleType.name.lowercase())
            put("module_path", command.modulePath.substringAfter("${command.moduleType.name.lowercase()}/"))
            put("target_host", command.target.host)
            command.target.port?.let { put("target_port", it) }
            command.payload?.let { put("payload", it) }
            if (command.options.isNotEmpty()) {
                putJsonObject("options") { command.options.forEach { (k, v) -> put(k, v) } }
            }
        }

        val submitted = try {
            client.submitJob("metasploit.module", UUID.randomUUID().toString(), params, command.timeoutMillis)
        } catch (e: SecurityBackendException) {
            return MetasploitExecutionResult.Failure("Could not submit Metasploit job to the Linux backend: ${e.message}", e)
        }

        val startedAt = System.currentTimeMillis()
        var last = submitted
        var cursor = submitted.next_cursor
        val stdout = StringBuilder()
        while (last.state !in JobState.TERMINAL) {
            if (isCancelled()) {
                runCatching { client.cancelJob(submitted.job_id) }
                // One more poll to learn the confirmed outcome — a cancel REQUEST is not the
                // same fact as the job having actually stopped; see SecurityBackendClient's
                // own doc comment on cancelJob.
            }
            if (System.currentTimeMillis() - startedAt > command.timeoutMillis + pollIntervalMillis * 3) {
                runCatching { client.cancelJob(submitted.job_id) }
                return MetasploitExecutionResult.Failure("Metasploit job '${submitted.job_id}' did not reach a terminal state within its timeout")
            }
            Thread.sleep(pollIntervalMillis)
            last = try {
                client.jobStatus(submitted.job_id, cursor, cancellation)
            } catch (e: SecurityBackendException) {
                return MetasploitExecutionResult.Failure("Lost contact with the Linux backend while polling job '${submitted.job_id}': ${e.message}", e)
            }
            last.output.forEach { stdout.append(it.text) }
            cursor = last.next_cursor
        }

        val durationMillis = System.currentTimeMillis() - startedAt
        return when (last.state) {
            JobState.COMPLETED -> MetasploitExecutionResult.Success(0, stdout.toString(), "", durationMillis)
            JobState.CANCELLED -> MetasploitExecutionResult.Failure("Metasploit job '${submitted.job_id}' was cancelled: ${last.error?.message}")
            JobState.INTERRUPTED -> MetasploitExecutionResult.Failure(
                "The Linux backend restarted while this job was running; its real outcome is unknown (job '${submitted.job_id}')",
            )
            else -> MetasploitExecutionResult.Failure("Metasploit job '${submitted.job_id}' failed: ${last.error?.message ?: "unknown reason"}")
        }
    }
}
