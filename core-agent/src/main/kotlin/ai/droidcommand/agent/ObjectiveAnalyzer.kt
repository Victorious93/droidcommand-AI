package ai.droidcommand.agent

/**
 * A structured decomposition of an objective, produced before planning
 * begins (ROADMAP-064). Every list defaults to empty rather than any field
 * being nullable, so an analyzer that has nothing to say about, say,
 * dependencies doesn't have to special-case that — an empty list already
 * means "none identified."
 */
data class ObjectiveAnalysis(
    val requirements: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val tasks: List<String> = emptyList(),
    val verificationCriteria: List<String> = emptyList(),
)

/**
 * Decomposes a raw objective string into an [ObjectiveAnalysis] — the
 * distinct analysis stage the master prompt's Phase 8 calls for, ahead of
 * planning. Deliberately just an interface here, the same split
 * [Planner]/`LlmPlanner` and [Logger]/`ConsoleLogger` already use: a real
 * implementation needs to actually understand the objective's text (in
 * practice, an LLM call), which `core-agent` has no dependency on — a
 * concrete analyzer belongs in a module like `core-llm` that does.
 */
fun interface ObjectiveAnalyzer {
    fun analyze(objective: String): ObjectiveAnalysis
}
