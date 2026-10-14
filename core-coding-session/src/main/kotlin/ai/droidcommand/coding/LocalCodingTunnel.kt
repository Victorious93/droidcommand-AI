package ai.droidcommand.coding

import java.util.UUID

/**
 * The on-device stand-in for the shared plan → edit → report tunnel.
 *
 * It records a plan, then an edit note, then a report, in that order. It does not call a cloud
 * model, does not open a network connection, and does not write anyone's repository. A vendor
 * backend (Cursor cloud agents, Codex, or anything else) is not implemented here.
 */
class LocalCodingTunnel(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val sessions = linkedMapOf<String, CodingSession>()

    fun start(goal: String): CodingSession {
        val text = goal.trim()
        require(text.isNotEmpty()) { "Goal is empty" }
        require(text.length <= CodingWire.MAX_GOAL_CHARS) { "Goal is longer than ${CodingWire.MAX_GOAL_CHARS} characters" }
        val session = CodingSession(
            id = newId(),
            goal = text,
            phase = CodingPhase.PLAN,
            plan = """
                Local stand-in. No cloud model was called and no repository was edited.
                Goal: $text
                1. Read the goal and name the files that would change.
                2. Draft the edit.
                3. Summarize what happened.
            """.trimIndent(),
        )
        sessions[session.id] = session
        return session
    }

    fun advance(id: String): CodingSession {
        val current = sessions[id] ?: throw NoSuchElementException("Unknown session $id")
        val next = when (current.phase) {
            CodingPhase.PLAN -> current.copy(
                phase = CodingPhase.EDIT,
                edit = "No files were written. A cloud editor that applies a diff is not connected.\n\nPlan this note is based on:\n${current.plan}",
            )
            CodingPhase.EDIT -> current.copy(
                phase = CodingPhase.REPORT,
                report = "Session ${current.id} finished on this device. The plan was recorded. The edit was not applied. Nothing was sent to a cloud coding service.",
            )
            CodingPhase.REPORT, CodingPhase.CANCELLED -> current
        }
        sessions[id] = next
        return next
    }

    fun cancel(id: String): CodingSession {
        val current = sessions[id] ?: throw NoSuchElementException("Unknown session $id")
        if (current.phase == CodingPhase.REPORT || current.phase == CodingPhase.CANCELLED) return current
        val next = current.copy(phase = CodingPhase.CANCELLED, report = "Cancelled before a report. No repository was edited.")
        sessions[id] = next
        return next
    }

    fun get(id: String): CodingSession? = sessions[id]

    /** Puts a session loaded from disk back where [advance] and [cancel] can find it. */
    fun remember(session: CodingSession) {
        sessions[session.id] = session
    }
}
