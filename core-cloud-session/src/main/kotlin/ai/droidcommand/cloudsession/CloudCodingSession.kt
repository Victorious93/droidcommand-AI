package ai.droidcommand.cloudsession

import java.time.Instant

/**
 * One step of a cloud coding session. The tunnel always walks these in order:
 * [PLAN] (decide the change), [EDIT] (apply it on the remote workspace), [REPORT] (summarize what
 * happened). [DONE], [FAILED] and [CANCELLED] are terminal.
 *
 * This is one shared session, not two products: a Cursor-cloud-agent style worker and a Codex style
 * report live in the same tunnel and the same [CloudCodingSession].
 */
enum class CloudSessionPhase {
    PLAN,
    EDIT,
    REPORT,
    DONE,
    FAILED,
    CANCELLED,
}

/** One recorded step. [body] is whatever the transport returned for that phase. */
data class CloudSessionEvent(
    val phase: CloudSessionPhase,
    val title: String,
    val body: String,
    val at: Instant,
)

/**
 * A cloud coding session: a goal plus the plan → edit → report trail. Immutable snapshots; the
 * tunnel replaces the whole value on each step.
 */
data class CloudCodingSession(
    val id: String,
    val goal: String,
    val phase: CloudSessionPhase,
    val events: List<CloudSessionEvent>,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val terminal: Boolean
        get() = phase == CloudSessionPhase.DONE || phase == CloudSessionPhase.FAILED || phase == CloudSessionPhase.CANCELLED
}
