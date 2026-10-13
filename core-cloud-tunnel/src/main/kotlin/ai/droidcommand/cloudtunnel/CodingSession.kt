package ai.droidcommand.cloudtunnel

import kotlinx.serialization.Serializable

/** Who wrote one turn in a shared coding session. */
enum class TunnelParticipant {
    USER,
    CURSOR_CLOUD_AGENT,
    CHATGPT_CODEX,

    /** A configured remote replied, and this client does not claim which product it was. */
    REMOTE_UNVERIFIED,
}

/** Plan, then edit, then report. One shared session walks this order. */
enum class TunnelPhase {
    PLAN,
    EDIT,
    REPORT,
    CLOSED,
}

/**
 * [STUB] never leaves the device. [REMOTE_PLANNED] means an HTTPS endpoint is configured and this
 * client will post a turn to it. Neither value means a live Cursor cloud agent or ChatGPT Codex
 * session was verified.
 */
enum class TunnelBackendKind {
    STUB,
    REMOTE_PLANNED,
}

@Serializable
data class TunnelEvent(
    val id: String,
    val participant: TunnelParticipant,
    val phase: TunnelPhase,
    val body: String,
    val createdAtEpochMs: Long,
)

@Serializable
data class CodingSession(
    val id: String,
    val title: String,
    val phase: TunnelPhase,
    val events: List<TunnelEvent>,
    val backend: TunnelBackendKind,
    val updatedAtEpochMs: Long,
)

data class TunnelExchange(
    val participant: TunnelParticipant,
    val phase: TunnelPhase,
    val body: String,
    val backend: TunnelBackendKind,
)
