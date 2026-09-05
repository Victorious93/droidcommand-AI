package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Planner
import ai.droidcommand.agent.PlannerDecision
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Bridges core-agent's [Planner] contract to an [LlmProvider]: a tool-call
 * response becomes [PlannerDecision.InvokeTool]; plain text is taken to mean
 * the model considers the objective satisfied and becomes
 * [PlannerDecision.Complete]; a provider error becomes [PlannerDecision.Abort]
 * rather than being thrown, so a provider failure surfaces through
 * [ai.droidcommand.agent.ObjectiveEngine]'s normal Failed state instead of
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
