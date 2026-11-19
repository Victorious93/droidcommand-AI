package ai.droidcommand.securitybackend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The Kotlin side of the versioned contract VictorLinux's `secprotocol.py` defines
 * (protocol_major 1). Canonical schema ownership is `secprotocol.py` in the VictorLinux
 * repo — see that file's own doc comment and `docs/security-backend.md` there for the
 * field-level reference. These data classes are shaped to decode exactly what that server
 * actually emits; [SecurityBackendClientTest]'s fixtures are byte-for-byte copies of real
 * responses captured from a live `secbackend.py` run on 2026-11-19 (see that test file's
 * own header for the exact commands used to capture them), not invented shapes — the two
 * repos are independent git checkouts, so there is no single shared fixtures directory the
 * way `fleet/fixtures/v1` is shared within VictorLinux alone; keeping both copies honest
 * requires re-copying a real captured response here whenever `secprotocol.py` changes.
 */
const val PROTOCOL_MAJOR = 1
const val PROTOCOL_MINOR = 0

/** Job lifecycle states. Mirrors `secprotocol.JOB_STATES`/`TERMINAL` exactly. */
object JobState {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val CANCELLING = "cancelling"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val INTERRUPTED = "interrupted"

    val TERMINAL = setOf(COMPLETED, FAILED, CANCELLED, INTERRUPTED)
}

@Serializable
data class JobSubmitRequest(
    val op: String,
    val idempotency_key: String,
    val params: JsonObject,
    val timeout_ms: Long = 180_000,
)

@Serializable
data class OutputChunk(val seq: Long, val text: String)

@Serializable
data class JobError(val code: String, val message: String)

@Serializable
data class JobResult(
    val summary: String? = null,
    val artifact_path: String? = null,
    val exit_evidence: String? = null,
)

@Serializable
data class JobStatusResponse(
    val job_id: String,
    val op: String,
    val state: String,
    val created_at: Long,
    val updated_at: Long,
    val timeout_ms: Long,
    val result: JobResult? = null,
    val error: JobError? = null,
    val output: List<OutputChunk> = emptyList(),
    val next_cursor: Long = 0,
)

@Serializable
data class JobListResponse(val jobs: List<JobStatusResponse> = emptyList())

@Serializable
data class OperationCapability(
    val available: Boolean,
    val reason: String? = null,
    val version: JsonElement? = null,
    val supported_vectors: List<String> = emptyList(),
    val unsupported_vectors: Map<String, String> = emptyMap(),
)

@Serializable
data class ProtocolVersion(val major: Int, val minor: Int)

@Serializable
data class CapabilitiesResponse(
    val protocol: ProtocolVersion,
    val operations: Map<String, OperationCapability>,
)

@Serializable
data class ErrorEnvelope(val error: JobError)

/**
 * Thrown for both transport failures (unreachable host, malformed response) and structured
 * backend error responses. [code] matches `secprotocol.ERROR_CODES` when the backend
 * produced it; a locally-detected transport problem uses `"transport_error"`, matching the
 * naming `secmsf.py`'s own client uses for the same distinction on the VictorLinux side.
 */
class SecurityBackendException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
