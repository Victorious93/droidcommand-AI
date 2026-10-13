package ai.droidcommand.coding

/**
 * One coding session on the shared tunnel: plan, then edit, then report.
 * [plan], [edit], and [report] fill in as the phase advances. An empty edit or report means that
 * phase has not run. Nothing in this type claims a repository was modified.
 */
data class CodingSession(
    val id: String,
    val goal: String,
    val phase: CodingPhase,
    val plan: String,
    val edit: String = "",
    val report: String = "",
)

enum class CodingPhase {
    PLAN,
    EDIT,
    REPORT,
    CANCELLED,
}
