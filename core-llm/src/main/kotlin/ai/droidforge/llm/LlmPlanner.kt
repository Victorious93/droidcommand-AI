package ai.droidforge.llm

import ai.droidforge.agent.ConversationContext
import ai.droidforge.agent.Planner
import ai.droidforge.agent.PlannerDecision
import ai.droidforge.agent.ToolResult
import ai.droidforge.agent.ToolSpec

/**
 * Bridges core-agent's [Planner] contract to an [LlmProvider]: a tool-call
 * response becomes [PlannerDecision.InvokeTool]; plain text is taken to mean
 * the model considers the objective satisfied and becomes
 * [PlannerDecision.Complete]; a provider error becomes [PlannerDecision.Abort]
 * rather than being thrown, so a provider failure surfaces through
 * [ai.droidforge.agent.ObjectiveEngine]'s normal Failed state instead of
 * crashing it.
 */
class LlmPlanner(private val provider: LlmProvider) : Planner {
    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ): PlannerDecision {
        val request = LlmRequest(
            systemPrompt = context.systemPrompt,
            messages = context.messages,
            tools = availableTools,
            temperature = provider.config.temperature,
            maxOutputTokens = provider.config.maxOutputTokens,
        )
        return when (val response = provider.complete(request)) {
            is LlmResponse.ToolCall -> PlannerDecision.InvokeTool(response.toolName, response.input)
            is LlmResponse.Text -> PlannerDecision.Complete(response.content)
            is LlmResponse.Error -> PlannerDecision.Abort(response.error.message)
        }
    }
}
