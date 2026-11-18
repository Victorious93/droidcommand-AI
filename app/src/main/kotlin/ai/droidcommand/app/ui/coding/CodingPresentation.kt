package ai.droidcommand.app.ui.coding

import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.coding.CodingPhase
import ai.droidcommand.coding.CodingSession

/** Where one of the three steps stands. */
enum class StepMark { DONE, CURRENT, TODO, STOPPED }

data class CodingStep(val label: String, val mark: StepMark)

/**
 * How the coding session screen shows progress. Pure, so it is JVM-tested. The session itself still comes from
 * [ai.droidcommand.coding.LocalCodingTunnel], a stand-in that records canned text; nothing here makes it do more.
 */
internal object CodingPresentation {
    private val LABELS = listOf("Plan", "Edit", "Report")

    /**
     * Plan, Edit, Report with where each stands. A cancelled session keeps no record of the phase it stopped in, but an
     * empty edit means it stopped during the plan, so that is what decides which step is marked stopped.
     */
    fun steps(session: CodingSession): List<CodingStep> {
        val marks = when (session.phase) {
            CodingPhase.PLAN -> listOf(StepMark.CURRENT, StepMark.TODO, StepMark.TODO)
            CodingPhase.EDIT -> listOf(StepMark.DONE, StepMark.CURRENT, StepMark.TODO)
            CodingPhase.REPORT -> listOf(StepMark.DONE, StepMark.DONE, StepMark.DONE)
            CodingPhase.CANCELLED ->
                if (session.edit.isEmpty()) {
                    listOf(StepMark.STOPPED, StepMark.TODO, StepMark.TODO)
                } else {
                    listOf(StepMark.DONE, StepMark.STOPPED, StepMark.TODO)
                }
        }
        return LABELS.zip(marks) { label, mark -> CodingStep(label, mark) }
    }

    fun phaseLabel(phase: CodingPhase): Pair<String, PillTone> = when (phase) {
        CodingPhase.PLAN -> "Planning" to PillTone.Neutral
        CodingPhase.EDIT -> "Editing" to PillTone.Neutral
        CodingPhase.REPORT -> "Done" to PillTone.Success
        CodingPhase.CANCELLED -> "Cancelled" to PillTone.Warning
    }

    /** The button that moves a session on, or null once there is nothing to move on to. */
    fun continueLabel(phase: CodingPhase): String? = when (phase) {
        CodingPhase.PLAN -> "Continue to edit"
        CodingPhase.EDIT -> "Continue to report"
        CodingPhase.REPORT, CodingPhase.CANCELLED -> null
    }

    /** The text of the newest step that has any, which is the one worth showing open. */
    fun latestTitleAndBody(session: CodingSession): Pair<String, String> = when {
        session.report.isNotEmpty() -> "Report" to session.report
        session.edit.isNotEmpty() -> "Edit" to session.edit
        else -> "Plan" to session.plan
    }

    /** The earlier steps' text, for the "show earlier steps" fold, newest last. Empty when there is nothing earlier. */
    fun earlier(session: CodingSession): List<Pair<String, String>> = buildList {
        val latest = latestTitleAndBody(session).first
        if (latest != "Plan") add("Plan" to session.plan)
        if (latest == "Report" && session.edit.isNotEmpty()) add("Edit" to session.edit)
    }
}
