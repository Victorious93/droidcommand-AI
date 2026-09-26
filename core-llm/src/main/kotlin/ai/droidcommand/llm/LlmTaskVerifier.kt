package ai.droidcommand.llm

import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.Message
import ai.droidcommand.agent.ObjectiveOutcome
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.Task
import ai.droidcommand.agent.TaskVerification
import ai.droidcommand.agent.TaskVerifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class CriterionJudgmentDto(
    val criterion: String,
    val met: Boolean,
    val reason: String = "",
)

@Serializable
private data class VerificationDto(
    val results: List<CriterionJudgmentDto> = emptyList(),
)

/**
 * Like [ObjectiveAnalyzer]'s prompt, this has never been exercised against
 * a real provider (this environment has no LLM credentials), only against
 * a scripted [LlmProvider] in tests.
 */
private val VERIFICATION_SYSTEM_PROMPT = """
    You verify whether a completed task actually met its verification
    criteria, using only the evidence given. Respond with ONLY a single JSON
    object, nothing else, with this shape:
    {"results": [{"criterion": string, "met": boolean, "reason": string}]}.
    Include exactly one entry per criterion, copying each criterion's text
    verbatim. Mark a criterion met only when the evidence shows it; if the
    evidence does not show it either way, mark it not met and say so in
    "reason".
""".trimIndent()

/**
 * A [TaskVerifier] that asks a real [LlmProvider] to judge a completed
 * [Task] against its free-text [Task.verificationCriteria] — the
 * `core-llm` counterpart to [ObjectiveAnalyzer], which produces those
 * criteria in the first place.
 *
 * The judgment sees the task's description, its criteria, the
 * [AgentState.Completed.summary] the planner finished with, and whatever
 * [evidence] returns for the task (typically the per-task
 * `ConversationContext.messages` a [ai.droidcommand.agent.TaskRunner]
 * captured, which carry the real tool results). With no [evidence]
 * supplied, the planner's own summary is the only evidence — weaker, and
 * the prompt tells the model to mark unsupported criteria as not met.
 *
 * Fails closed: a provider error, a tool call, unparseable JSON, or a
 * judgment that omits any criterion is [TaskVerification.Inconclusive],
 * never [TaskVerification.Passed].
 */
class LlmTaskVerifier(
    private val provider: LlmProvider,
    private val evidence: (Task) -> List<Message> = { emptyList() },
) : TaskVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override fun verify(task: Task, outcome: ObjectiveOutcome): TaskVerification {
        val summary = (outcome.finalState as? AgentState.Completed)?.summary
            ?: return TaskVerification.Inconclusive("Task did not complete (final state ${outcome.finalState::class.simpleName})")

        val prompt = buildString {
            appendLine("Task: ${task.description}")
            appendLine("Completion summary: $summary")
            appendLine("Criteria:")
            task.verificationCriteria.forEach { appendLine("- $it") }
        }
        val request = LlmRequest(
            systemPrompt = VERIFICATION_SYSTEM_PROMPT,
            messages = evidence(task) + Message(Role.USER, prompt),
        )

        return when (val response = provider.complete(request)) {
            is LlmResponse.Error -> TaskVerification.Inconclusive("Provider failed (${response.error::class.simpleName}): ${response.error.message}")
            is LlmResponse.ToolCall -> TaskVerification.Inconclusive("Provider returned a tool call, but no tools were offered for verification")
            is LlmResponse.Text -> judge(task, response.content)
        }
    }

    private fun judge(task: Task, text: String): TaskVerification {
        val dto = try {
            json.decodeFromString(VerificationDto.serializer(), text)
        } catch (e: SerializationException) {
            return TaskVerification.Inconclusive("Unparseable judgment: ${e.message ?: "invalid JSON"}")
        }

        val byCriterion = dto.results.associateBy { it.criterion.trim() }
        val missing = task.verificationCriteria.filter { it.trim() !in byCriterion }
        if (missing.isNotEmpty()) {
            return TaskVerification.Inconclusive("Judgment did not address: ${missing.joinToString("; ")}")
        }

        val unmet = task.verificationCriteria.filter { !byCriterion.getValue(it.trim()).met }
        if (unmet.isEmpty()) return TaskVerification.Passed
        val reason = unmet.joinToString("; ") { "$it: ${byCriterion.getValue(it.trim()).reason}" }
        return TaskVerification.Failed(unmet, reason)
    }
}
