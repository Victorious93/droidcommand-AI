package ai.droidforge.agent

sealed class PlannerDecision {
    data class InvokeTool(val toolName: String, val input: Map<String, String>) : PlannerDecision()
    data class Complete(val summary: String) : PlannerDecision()
    data class Abort(val reason: String) : PlannerDecision()
}

/**
 * Chooses the next step of an objective loop given the objective, the
 * conversation so far, the tools available, and the previous tool result (if
 * any). Implementations may be LLM-backed (see core-llm's LlmPlanner) or
 * scripted, for testing [ObjectiveEngine] without a real model.
 */
interface Planner {
    fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision
}
