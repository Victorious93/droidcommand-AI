package ai.droidcommand.security

import java.time.Instant

enum class AuditEventType {
    ACCESS_GRANTED,
    ACCESS_DENIED,
    GRANT_ISSUED,
    GRANT_CONSUMED,
    GRANT_REVOKED,
    GRANT_DENIED,
    INITIATOR_DENIED,
    SECRET_ACCESSED,
    SECRET_REVOKED,
    APPROVAL_APPROVED,
    APPROVAL_DENIED,
    APPROVAL_TIMED_OUT,
    APPROVAL_UNAVAILABLE,
}

data class AuditEvent(
    val type: AuditEventType,
    val subject: String,
    val detail: String,
    val timestamp: Instant = Instant.now(),
)

/**
 * Records security-relevant decisions. [record] returns whether the event
 * was actually stored — a bounded implementation returns `false` once full
 * rather than silently evicting an older entry, so a caller can choose to
 * fail closed (deny the action it was about to log) instead of running a
 * sensitive/root action that would go unaudited.
 */
interface AuditLog {
    fun record(event: AuditEvent): Boolean
}

/**
 * In-memory, bounded audit trail. Deliberately fails closed at [capacity]
 * (refuses new entries) rather than evicting the oldest one — an audit
 * trail that silently drops old entries once full can hide exactly the
 * privileged action it exists to prove happened.
 */
class InMemoryAuditLog(private val capacity: Int = 10_000) : AuditLog {
    private val events = mutableListOf<AuditEvent>()

    @Synchronized
    override fun record(event: AuditEvent): Boolean {
        if (events.size >= capacity) return false
        events += event
        return true
    }

    @Synchronized
    fun all(): List<AuditEvent> = events.toList()
}
