package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.Task
import ai.droidcommand.agent.TaskGraph
import ai.droidcommand.agent.TaskGraphError
import ai.droidcommand.agent.TaskGraphResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The outcome of [ObjectiveAnalyzer.analyze]: a typed result instead of a
 * thrown exception, mirroring [LlmKnowledgeExtractor]'s
 * [KnowledgeExtractionResult]. [InvalidTaskGraph] is distinct from
 * [Malformed]: the provider's JSON parsed correctly, but the task graph it
 * described isn't a valid DAG (a cycle, or a dependency naming a task that
 * doesn't exist) — a structurally different failure than unparseable text,
 * caught by [ai.droidcommand.agent.TaskGraph.from] rather than silently
 * accepted as [Success].
 */
sealed class ObjectiveAnalysisResult {
    data class Success(val requirements: List<String>, val constraints: List<String>, val taskGraph: TaskGraph) : ObjectiveAnalysisResult()

    data class InvalidTaskGraph(val requirements: List<String>, val constraints: List<String>, val errors: List<TaskGraphError>) :
        ObjectiveAnalysisResult()

    data class Malformed(val raw: String, val reason: String) : ObjectiveAnalysisResult()

    data class ProviderFailed(val error: LlmError) : ObjectiveAnalysisResult()
}

@Serializable
private data class TaskDto(
    val id: String,
    val description: String,
    val dependencies: List<String> = emptyList(),
    val verificationCriteria: List<String> = emptyList(),
)

@Serializable
private data class ObjectiveAnalysisDto(
    val requirements: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
)

/**
 * A prompt asking the model to decompose a high-level objective into
 * requirements, constraints, and a dependency-ordered task breakdown, as a
 * single JSON object. Like [LlmKnowledgeExtractor]'s extraction prompt,
 * this has never been exercised against a real provider — this environment
 * has no LLM credentials — only against a scripted [LlmProvider] in tests.
 */
private val ANALYSIS_SYSTEM_PROMPT = """
    You analyze a high-level objective before it is planned. Respond with
    ONLY a single JSON object, nothing else, with this shape:
    {"requirements": string[], "constraints": string[], "tasks": [
      {"id": string, "description": string, "dependencies": string[], "verificationCriteria": string[]}
    ]}.
    Each task's "dependencies" must reference other tasks' "id" values —
    never invent a dependency on a task that isn't in "tasks". Decompose
    the objective into as many or as few tasks as it genuinely needs; a
    single self-contained objective may produce exactly one task.
""".trimIndent()

/**
 * Converts a high-level Forge objective into structured requirements,
 * constraints, and a dependency-ordered [TaskGraph] (ROADMAP-064), via a
 * real [LlmProvider] call — mirroring [LlmKnowledgeExtractor]'s exact
 * adapter role for a different [ai.droidcommand.agent] concept.
 *
 * Deliberately does **not** mutate [ConversationContext] the way
 * [ai.droidcommand.agent.ObjectiveEngine.run] does — [analyze] builds its
 * own request from a snapshot of [ConversationContext.messages] plus
 * [objective] as a trailing user message, so calling this never has a
 * surprising side effect on a context the caller reuses elsewhere.
 *
 * Not invoked automatically from
 * [ai.droidcommand.agent.ObjectiveEngine]/[ai.droidcommand.agent.DroidCommandSession]:
 * see [AnalyzedObjectiveRunner] for the caller-chosen entry point that uses
 * this analyzer instead of calling [ai.droidcommand.agent.ObjectiveEngine.run]
 * directly with a raw objective string. `ObjectiveEngine.run`'s existing
 * behavior for every current caller is completely unchanged by this file.
 */
class ObjectiveAnalyzer(private val provider: LlmProvider) {
    private val json = Json { ignoreUnknownKeys = true }

    fun analyze(objective: String, context: ConversationContext = ConversationContext()): ObjectiveAnalysisResult {
        val request = LlmRequest(
            systemPrompt = ANALYSIS_SYSTEM_PROMPT,
            messages = context.messages + Message(Role.USER, objective),
        )
        return when (val response = provider.complete(request)) {
            is LlmResponse.Error -> ObjectiveAnalysisResult.ProviderFailed(response.error)
            is LlmResponse.ToolCall -> ObjectiveAnalysisResult.Malformed(
                raw = "tool_call:${response.toolName}",
                reason = "Provider returned a tool call, but no tools were offered for analysis",
            )
            is LlmResponse.Text -> parse(response.content)
        }
    }

    private fun parse(text: String): ObjectiveAnalysisResult {
        val dto = try {
            json.decodeFromString(ObjectiveAnalysisDto.serializer(), text)
        } catch (e: SerializationException) {
            return ObjectiveAnalysisResult.Malformed(text, e.message ?: "invalid JSON")
        }

        val tasks = dto.tasks.map {
            Task(id = it.id, description = it.description, dependencies = it.dependencies.toSet(), verificationCriteria = it.verificationCriteria)
        }
        return when (val result = TaskGraph.from(tasks)) {
            is TaskGraphResult.Invalid -> ObjectiveAnalysisResult.InvalidTaskGraph(dto.requirements, dto.constraints, result.errors)
            is TaskGraphResult.Valid -> ObjectiveAnalysisResult.Success(dto.requirements, dto.constraints, result.graph)
        }
    }
}
