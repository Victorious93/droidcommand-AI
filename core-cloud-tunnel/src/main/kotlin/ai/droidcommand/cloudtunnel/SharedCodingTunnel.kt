package ai.droidcommand.cloudtunnel

import java.util.UUID

class UnknownCodingSession(id: String) : IllegalArgumentException("Unknown coding session '$id'")

class ClosedCodingSession(id: String) : IllegalStateException("Coding session '$id' is closed")

/**
 * One session both the Cursor-cloud-agent slot and the ChatGPT-Codex slot write into.
 * The user instruction is stored first, then [transport] appends the next participant turn and
 * chooses the phase. Closing a session records nothing further.
 */
class SharedCodingTunnel(
    private val transport: CodingTunnelTransport,
    private val store: CodingSessionStore,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    val backend: TunnelBackendKind get() = transport.backend
    val statusNote: String get() = transport.statusNote

    fun sessions(): List<CodingSession> = store.list()

    fun open(title: String): CodingSession {
        val trimmed = title.trim().ifEmpty { "Untitled session" }
        val session = CodingSession(
            id = ids(),
            title = trimmed,
            phase = TunnelPhase.PLAN,
            events = emptyList(),
            backend = transport.backend,
            updatedAtEpochMs = clock(),
        )
        store.save(session)
        return session
    }

    fun get(id: String): CodingSession? = store.get(id)

    fun submit(id: String, instruction: String): CodingSession {
        val trimmed = instruction.trim()
        require(trimmed.isNotEmpty()) { "Instruction must not be blank" }
        val current = store.get(id) ?: throw UnknownCodingSession(id)
        if (current.phase == TunnelPhase.CLOSED) throw ClosedCodingSession(id)
        val now = clock()
        val withUser = current.copy(
            events = current.events + TunnelEvent(ids(), TunnelParticipant.USER, current.phase, trimmed, now),
            updatedAtEpochMs = now,
        )
        val exchange = transport.exchange(withUser, trimmed)
        val recorded = withUser.copy(
            phase = exchange.phase,
            backend = exchange.backend,
            events = withUser.events + TunnelEvent(ids(), exchange.participant, exchange.phase, exchange.body, clock()),
            updatedAtEpochMs = clock(),
        )
        store.save(recorded)
        return recorded
    }

    fun close(id: String): CodingSession {
        val current = store.get(id) ?: throw UnknownCodingSession(id)
        if (current.phase == TunnelPhase.CLOSED) return current
        val closed = current.copy(phase = TunnelPhase.CLOSED, updatedAtEpochMs = clock())
        store.save(closed)
        return closed
    }
}
