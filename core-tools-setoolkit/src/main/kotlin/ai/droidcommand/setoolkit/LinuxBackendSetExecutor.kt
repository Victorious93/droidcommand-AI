package ai.droidcommand.setoolkit

import ai.droidcommand.remote.RequestCancellation
import ai.droidcommand.securitybackend.JobState
import ai.droidcommand.securitybackend.SecurityBackendClient
import ai.droidcommand.securitybackend.SecurityBackendException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * [SetExecutor] that runs an attack vector through VictorLinux's `secbackend.py` — the
 * Linux-backend counterpart to [TermuxSetExecutor]'s on-device Kali proot path. The backend
 * itself only actually runs [SetAttackVector.QRCODE_GENERATOR] unattended today (see that
 * repo's `secset.py`/`secprotocol.py` doc comments and `docs/security-backend.md#setoolkit-coverage`
 * for exactly which vectors and why) — every other vector reaches the backend and is
 * rejected server-side with `capability_missing` and the SAME specific reason
 * `capabilities()` already reports, so a caller that skipped checking capabilities first
 * still gets an honest, specific failure rather than a hang or a guessed attempt.
 *
 * **Verified against a real running `secbackend.py` instance backed by a real installed
 * `setoolkit` on 2026-11-19** (see VictorLinux's `docs/security-backend.md`) — this Kotlin
 * class's own tests are against a fake transport built from that real session's responses,
 * not an invented shape; no session has pointed this specific class at a real running
 * `secbackend.py` end to end yet (same gap noted on [ai.droidcommand.metasploit.LinuxBackendMetasploitExecutor]).
 */
class LinuxBackendSetExecutor(
    private val client: SecurityBackendClient,
    private val pollIntervalMillis: Long = 1_000,
) : SetExecutor {
    override fun isAvailable(): Boolean = runCatching {
        client.capabilities().operations["setoolkit.attack"]?.available == true
    }.getOrDefault(false)

    override fun execute(command: SetCommand, isCancelled: () -> Boolean): SetExecutionResult {
        val cancellation = RequestCancellation()
        val params = buildJsonObject {
            put("attack_vector", command.attackVector.name)
            command.target.host?.let { put("target_host", it) }
            command.target.emailAddress?.let { put("target_email", it) }
        }

        val submitted = try {
            client.submitJob("setoolkit.attack", UUID.randomUUID().toString(), params, command.timeoutMillis)
        } catch (e: SecurityBackendException) {
            return SetExecutionResult.Failure("Could not submit SET job to the Linux backend: ${e.message}", e)
        }

        val startedAt = System.currentTimeMillis()
        var last = submitted
        var cursor = submitted.next_cursor
        val stdout = StringBuilder()
        while (last.state !in JobState.TERMINAL) {
            if (isCancelled()) {
                runCatching { client.cancelJob(submitted.job_id) }
            }
            if (System.currentTimeMillis() - startedAt > command.timeoutMillis + pollIntervalMillis * 3) {
                runCatching { client.cancelJob(submitted.job_id) }
                return SetExecutionResult.Failure("SET job '${submitted.job_id}' did not reach a terminal state within its timeout")
            }
            Thread.sleep(pollIntervalMillis)
            last = try {
                client.jobStatus(submitted.job_id, cursor, cancellation)
            } catch (e: SecurityBackendException) {
                return SetExecutionResult.Failure("Lost contact with the Linux backend while polling job '${submitted.job_id}': ${e.message}", e)
            }
            last.output.forEach { stdout.append(it.text) }
            cursor = last.next_cursor
        }

        val durationMillis = System.currentTimeMillis() - startedAt
        return when (last.state) {
            JobState.COMPLETED -> SetExecutionResult.Success(0, stdout.toString(), "", durationMillis)
            JobState.CANCELLED -> SetExecutionResult.Failure("SET job '${submitted.job_id}' was cancelled: ${last.error?.message}")
            JobState.INTERRUPTED -> SetExecutionResult.Failure(
                "The Linux backend restarted while this job was running; its real outcome is unknown (job '${submitted.job_id}')",
            )
            else -> SetExecutionResult.Failure("SET job '${submitted.job_id}' failed: ${last.error?.message ?: "unknown reason"}")
        }
    }
}
