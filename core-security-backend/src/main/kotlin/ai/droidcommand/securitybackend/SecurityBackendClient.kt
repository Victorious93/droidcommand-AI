package ai.droidcommand.securitybackend

import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RequestCancellation
import ai.droidcommand.remote.RequestCancelled
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * Configuration for one VictorLinux security backend. Stored wherever this app stores other
 * remote-endpoint config (the owner's own encrypted vault) — never bundled with the Metasploit
 * RPC credentials themselves, which stay on VictorLinux and are never sent to or stored on the
 * phone; this client only ever presents the backend's own API key.
 */
data class SecurityBackendConfig(
    val baseUrl: String,
    val apiKey: String,
    val connectTimeoutMillis: Long = 10_000,
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Talks to VictorLinux's `secbackend.py` over the contract in `SecurityBackendProtocol.kt`.
 * Every method can throw [SecurityBackendException] — a transport failure ("transport_error")
 * and a structured backend error (its own `code`) are both reported through it, never through
 * a plain [IOException] a caller might mistake for "the tool itself failed" (see that module's
 * own analogous distinction in `secmsf.py`/`secset.py`).
 */
class SecurityBackendClient(
    private val transport: HttpTransport,
    private val config: SecurityBackendConfig,
) {
    private fun url(path: String) = config.baseUrl.trimEnd('/') + path

    private fun request(method: String, path: String, body: String? = null, cancellation: RequestCancellation? = null) =
        transport.send(
            HttpRequestSpec(
                method = method,
                url = url(path),
                headers = buildMap {
                    put("Authorization", "Bearer ${config.apiKey}")
                    if (body != null) put("Content-Type", "application/json")
                },
                body = body,
                connectTimeoutMillis = config.connectTimeoutMillis,
                cancellation = cancellation,
            ),
        )

    private fun requireOk(method: String, path: String, body: String?, cancellation: RequestCancellation?): String {
        val response = try {
            request(method, path, body, cancellation)
        } catch (e: RequestCancelled) {
            throw e
        } catch (e: IOException) {
            throw SecurityBackendException("transport_error", "Could not reach the security backend: ${e.message}", e)
        }
        if (response.statusCode >= 400) {
            val code = runCatching { json.decodeFromString<ErrorEnvelope>(response.body).error.code }
                .getOrDefault("transport_error")
            val message = runCatching { json.decodeFromString<ErrorEnvelope>(response.body).error.message }
                .getOrDefault("security backend returned HTTP ${response.statusCode}")
            throw SecurityBackendException(code, message)
        }
        return response.body
    }

    /**
     * Capability discovery: what the backend's own protocol version is, which tools are
     * actually installed/reachable right now (never just "VictorLinux is reachable"), and —
     * for `setoolkit.attack` — exactly which attack vectors are supported today and why the
     * rest are not. Callers must show `available=false`/`reason` to the operator rather than
     * attempt a submit anyway; the backend also enforces this server-side (`capability_missing`)
     * but a client that already knows should not force an operator through a doomed attempt.
     */
    fun capabilities(): CapabilitiesResponse =
        json.decodeFromString(requireOk("GET", "/security/v1/capabilities", null, null))

    /**
     * Submits a job. [idempotencyKey] must be the SAME value across a network retry of the
     * same logical request (callers own this — see [SecurityBackendClient]'s own callers for
     * how they derive one) so a dropped response never causes a second, real run of the same
     * exploit/SET module; a retried key with different [params] is rejected with
     * `idempotency_conflict`, which callers must surface, never silently resubmit past.
     */
    fun submitJob(op: String, idempotencyKey: String, params: JsonObject, timeoutMillis: Long): JobStatusResponse {
        val body = json.encodeToString(JobSubmitRequest(op, idempotencyKey, params, timeoutMillis))
        return json.decodeFromString(requireOk("POST", "/security/v1/jobs", body, null))
    }

    /**
     * Reads a job's current state and any output since [sinceCursor]. Pass the previous
     * response's `next_cursor` to resume without duplicating or losing output lines across a
     * reconnect — this client never re-requests from cursor 0 just because a previous poll's
     * connection dropped.
     */
    fun jobStatus(jobId: String, sinceCursor: Long = 0, cancellation: RequestCancellation? = null): JobStatusResponse =
        json.decodeFromString(requireOk("GET", "/security/v1/jobs/$jobId?since=$sinceCursor", null, cancellation))

    /**
     * Requests cancellation. Returns the job's state immediately after the request is
     * accepted, which is `"cancelling"` (confirmation of a stop is not yet known) or already
     * terminal (the job had already finished — cancelling a finished job is a no-op, not an
     * error). Callers must poll [jobStatus] afterward to learn the confirmed outcome, exactly
     * like VictorLinux's own `secjobs.py` documents for itself.
     */
    fun cancelJob(jobId: String): JobStatusResponse =
        json.decodeFromString(requireOk("POST", "/security/v1/jobs/$jobId/cancel", "{}", null))

    fun listJobs(): JobListResponse =
        json.decodeFromString(requireOk("GET", "/security/v1/jobs", null, null))
}
