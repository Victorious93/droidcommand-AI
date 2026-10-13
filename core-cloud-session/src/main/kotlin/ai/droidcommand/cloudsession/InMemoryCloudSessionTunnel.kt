package ai.droidcommand.cloudsession

import java.time.Instant
import java.util.UUID

/**
 * Process-local tunnel. Sessions live in memory only (they are not a stand-in for a remote workspace)
 * and are capped at [maxSessions], dropping the oldest finished ones first.
 *
 * [transport] defaults to [StubCloudSessionTransport]. Passing a different transport is the seam a
 * future live backend would use; none is shipped here.
 */
class InMemoryCloudSessionTunnel(
    override val transport: CloudSessionTransport = StubCloudSessionTransport(),
    override val backendStatus: String = PLANNED_BACKEND_STATUS,
    private val maxSessions: Int = MAX_SESSIONS,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : CloudSessionTunnel {
    private val lock = Any()
    private val sessions = LinkedHashMap<String, CloudCodingSession>()

    override fun list(): List<CloudCodingSession> = synchronized(lock) { sessions.values.reversed() }

    override fun get(id: String): CloudCodingSession? = synchronized(lock) { sessions[id] }

    override fun open(goal: String): CloudCodingSession {
        val text = goal.trim()
        require(text.isNotEmpty()) { "A cloud session needs a goal." }
        require(text.length <= MAX_GOAL) { "Goal is too long (max $MAX_GOAL characters)." }
        val at = now()
        val plan = transport.plan(text)
        val session = CloudCodingSession(
            id = newId(),
            goal = text,
            phase = CloudSessionPhase.PLAN,
            events = listOf(CloudSessionEvent(CloudSessionPhase.PLAN, "Plan", plan, at)),
            createdAt = at,
            updatedAt = at,
        )
        synchronized(lock) {
            sessions[session.id] = session
            trimLocked()
        }
        return session
    }

    override fun advance(id: String): CloudCodingSession = synchronized(lock) {
        val current = sessions[id] ?: throw IllegalArgumentException("Unknown cloud session.")
        if (current.terminal) return@synchronized current
        val at = now()
        val next = when (current.phase) {
            CloudSessionPhase.PLAN -> {
                val plan = current.events.last { it.phase == CloudSessionPhase.PLAN }.body
                val edit = transport.edit(current.goal, plan)
                current.copy(
                    phase = CloudSessionPhase.EDIT,
                    events = current.events + CloudSessionEvent(CloudSessionPhase.EDIT, "Edit", edit, at),
                    updatedAt = at,
                )
            }
            CloudSessionPhase.EDIT -> {
                val plan = current.events.last { it.phase == CloudSessionPhase.PLAN }.body
                val edit = current.events.last { it.phase == CloudSessionPhase.EDIT }.body
                val report = transport.report(current.goal, plan, edit)
                current.copy(
                    phase = CloudSessionPhase.REPORT,
                    events = current.events + CloudSessionEvent(CloudSessionPhase.REPORT, "Report", report, at),
                    updatedAt = at,
                )
            }
            CloudSessionPhase.REPORT -> current.copy(
                phase = CloudSessionPhase.DONE,
                events = current.events + CloudSessionEvent(
                    CloudSessionPhase.DONE,
                    "Done",
                    "Session closed. The report above is the stub result; a live backend would return its own.",
                    at,
                ),
                updatedAt = at,
            )
            CloudSessionPhase.DONE, CloudSessionPhase.FAILED, CloudSessionPhase.CANCELLED -> current
        }
        sessions[id] = next
        next
    }

    override fun cancel(id: String): CloudCodingSession = synchronized(lock) {
        val current = sessions[id] ?: throw IllegalArgumentException("Unknown cloud session.")
        if (current.terminal) return@synchronized current
        val at = now()
        val next = current.copy(
            phase = CloudSessionPhase.CANCELLED,
            events = current.events + CloudSessionEvent(
                CloudSessionPhase.CANCELLED,
                "Cancelled",
                "Cancelled before a live worker was attached.",
                at,
            ),
            updatedAt = at,
        )
        sessions[id] = next
        next
    }

    private fun trimLocked() {
        while (sessions.size > maxSessions) {
            val oldestFinished = sessions.values.firstOrNull { it.terminal }
            val drop = oldestFinished ?: sessions.values.first()
            sessions.remove(drop.id)
        }
    }

    private companion object {
        const val MAX_SESSIONS = 30
        const val MAX_GOAL = 4_000
    }
}
