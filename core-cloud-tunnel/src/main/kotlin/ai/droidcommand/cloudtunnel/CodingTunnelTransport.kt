package ai.droidcommand.cloudtunnel

import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns one user instruction into the next participant message. Implementations must not invent a
 * successful edit. A stub says it is a stub. A remote reports the HTTP result.
 */
interface CodingTunnelTransport {
    val backend: TunnelBackendKind
    val statusNote: String

    fun exchange(session: CodingSession, instruction: String): TunnelExchange
}

/**
 * Deterministic stand-in for a shared Cursor cloud-agent and ChatGPT Codex session.
 * Plan turns are attributed to the Cursor slot, edit turns to the Codex slot, and the report
 * names both. No files are changed and no network call is made.
 */
class StubCodingTunnelTransport : CodingTunnelTransport {
    override val backend = TunnelBackendKind.STUB

    override val statusNote: String =
        "Local stub. Live Cursor cloud-agent and ChatGPT Codex backends are PLANNED and are not called. " +
            "Nothing in this session edits a repository."

    override fun exchange(session: CodingSession, instruction: String): TunnelExchange {
        val text = instruction.trim()
        val (participant, phase, body) = when (session.phase) {
            TunnelPhase.PLAN -> Triple(
                TunnelParticipant.CURSOR_CLOUD_AGENT,
                TunnelPhase.EDIT,
                "Plan (local stub, not a Cursor cloud agent):\n" +
                    "1. Read the request: $text\n" +
                    "2. No repository was opened and no files will be changed.\n" +
                    "3. The next turn is the edit phase, answered by the local ChatGPT Codex stand-in.",
            )
            TunnelPhase.EDIT -> Triple(
                TunnelParticipant.CHATGPT_CODEX,
                TunnelPhase.REPORT,
                "Edit (local stub, not ChatGPT Codex):\n" +
                    "No files were read or written. Instruction kept for the report: $text",
            )
            TunnelPhase.REPORT -> Triple(
                TunnelParticipant.CURSOR_CLOUD_AGENT,
                TunnelPhase.REPORT,
                "Report (local stub): session '${session.title}' finished plan → edit → report on this device. " +
                    "Follow-up: $text. No remote agent ran.",
            )
            TunnelPhase.CLOSED -> Triple(
                TunnelParticipant.REMOTE_UNVERIFIED,
                TunnelPhase.CLOSED,
                "This session is closed.",
            )
        }
        return TunnelExchange(participant, phase, body, backend)
    }
}

@Serializable
internal data class TunnelTurnRequest(
    val sessionId: String,
    val phase: String,
    val instruction: String,
    val participants: List<String>,
)

@Serializable
internal data class TunnelTurnResponse(
    val participant: String = "",
    val phase: String = "",
    val body: String = "",
)

/**
 * Posts one JSON turn through [client]. A 2xx body is shown as an unverified remote reply unless
 * it is JSON naming a known participant and phase. Any other outcome stays on the current phase
 * and records the failure. This is not a Cursor or Codex protocol.
 */
class RemoteCodingTunnelTransport(
    private val client: RemoteClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : CodingTunnelTransport {
    override val backend = TunnelBackendKind.REMOTE_PLANNED

    override val statusNote: String =
        "A tunnel endpoint is configured. This client posts a JSON turn and shows the reply or the error. " +
            "A live Cursor cloud-agent session and a live ChatGPT Codex session are PLANNED; " +
            "a response is not treated as proof that either product ran."

    override fun exchange(session: CodingSession, instruction: String): TunnelExchange {
        if (session.phase == TunnelPhase.CLOSED) {
            return TunnelExchange(
                TunnelParticipant.REMOTE_UNVERIFIED,
                TunnelPhase.CLOSED,
                "This session is closed.",
                backend,
            )
        }
        val request = TunnelTurnRequest(
            sessionId = session.id,
            phase = session.phase.name,
            instruction = instruction.trim(),
            participants = listOf(TunnelParticipant.CURSOR_CLOUD_AGENT.name, TunnelParticipant.CHATGPT_CODEX.name),
        )
        val result = client.send(
            path = "/v1/coding-sessions/${session.id}/turns",
            method = "POST",
            headers = mapOf("Content-Type" to "application/json"),
            body = json.encodeToString(TunnelTurnRequest.serializer(), request),
        )
        return when (result) {
            is RemoteResult.Failure -> TunnelExchange(
                participant = TunnelParticipant.REMOTE_UNVERIFIED,
                phase = session.phase,
                body = "Remote tunnel failed (${result.statusCode ?: "no status"}): ${result.reason}",
                backend = backend,
            )
            is RemoteResult.Success -> parseSuccess(session, result.body)
        }
    }

    private fun parseSuccess(session: CodingSession, body: String): TunnelExchange {
        val parsed = runCatching { json.decodeFromString(TunnelTurnResponse.serializer(), body) }.getOrNull()
        if (parsed == null || parsed.body.isBlank()) {
            return TunnelExchange(
                participant = TunnelParticipant.REMOTE_UNVERIFIED,
                phase = session.phase,
                body = "Remote returned a body this client does not treat as a coding turn. Phase left unchanged.",
                backend = backend,
            )
        }
        val participant = runCatching { TunnelParticipant.valueOf(parsed.participant) }.getOrDefault(TunnelParticipant.REMOTE_UNVERIFIED)
        val phase = runCatching { TunnelPhase.valueOf(parsed.phase) }.getOrDefault(session.phase)
        val prefix = if (participant == TunnelParticipant.REMOTE_UNVERIFIED) {
            "Unverified remote reply. "
        } else {
            "Remote named ${participant.name}. This client did not verify that product. "
        }
        return TunnelExchange(participant, phase, prefix + parsed.body, backend)
    }
}
