package ai.droidcommand.agent

import java.time.Instant
import java.util.UUID

enum class PlanStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

enum class PlanStepStatus { RUNNING, SUCCEEDED, PARTIAL, FAILED }

/**
 * One tool invocation made during a [PlanRun], recorded in the order the planner chose it.
 * [output] is [ToolResult.describe]'s text once the tool has returned, `null` while it runs.
 */
data class PlanStep(
    val index: Int,
    val toolName: String,
    val input: Map<String, String>,
    val status: PlanStepStatus,
    val output: String? = null,
)

/**
 * A read-only snapshot of one objective run: what was asked, which tools the planner invoked and how
 * each ended, and how the run finished. [summary] is the planner's final answer for a
 * [PlanStatus.COMPLETED] run, or the failure/cancel reason otherwise.
 *
 * Steps are recorded as they happen, not pre-planned: [ObjectiveEngine] asks its [Planner] for one
 * decision at a time, so there is no list of future steps to show before they run.
 */
data class PlanRun(
    val id: String,
    val objective: String,
    val status: PlanStatus,
    val steps: List<PlanStep>,
    val summary: String? = null,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
)

/**
 * Runs one Forge-mode objective through [ObjectiveEngine] and reports it as a [PlanRun], emitting a
 * fresh snapshot to `onUpdate` every time the run starts, a step starts or finishes, or the run ends.
 *
 * Every [run] builds its own [AgentStateMachine] and hands it to [newRunner] to build the
 * [ToolRunner]: the state machine is terminal-sticky (once Completed/Failed/Cancelled, every further
 * transition throws), so one machine can drive exactly one objective. [newRunner] must therefore
 * return an executor bound to the machine it is given — never a shared, long-lived one.
 *
 * [onUpdate] is called on the thread running [run]. Each [run] call is independent of any other;
 * callers that want one run at a time serialize them themselves.
 */
class PlanRunner(
    private val registry: ToolRegistry,
    private val planner: Planner,
    private val newRunner: (AgentStateMachine) -> ToolRunner,
    private val maxIterations: Int = 15,
    private val reservedFinalizationIterations: Int = 2,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    fun run(
        objective: String,
        isCancelled: () -> Boolean = { false },
        onUpdate: (PlanRun) -> Unit = {},
    ): PlanRun {
        val machine = AgentStateMachine()
        val delegate = newRunner(machine)
        val steps = mutableListOf<PlanStep>()
        var snapshot = PlanRun(newId(), objective, PlanStatus.RUNNING, emptyList(), startedAt = now())
        fun publish(update: (PlanRun) -> PlanRun) {
            snapshot = update(snapshot)
            onUpdate(snapshot)
        }
        publish { it }

        val recording = object : ToolRunner {
            override fun run(
                toolName: String,
                input: Map<String, String>,
                retryPolicy: RetryPolicy,
                isCancelled: () -> Boolean,
                mode: AgentMode?,
                initiator: Initiator?,
                grantId: String?,
            ): ToolResult {
                val index = steps.size + 1
                steps += PlanStep(index, toolName, input, PlanStepStatus.RUNNING)
                publish { it.copy(steps = steps.toList()) }
                val result = try {
                    delegate.run(toolName, input, retryPolicy, isCancelled, mode, initiator, grantId)
                } catch (t: Throwable) {
                    // CancellationRequested / UnknownToolException / anything else: record the step as
                    // failed, then rethrow so ObjectiveEngine handles it exactly as it would unrecorded.
                    steps[index - 1] = steps[index - 1].copy(status = PlanStepStatus.FAILED, output = t.message ?: t.javaClass.simpleName)
                    publish { it.copy(steps = steps.toList()) }
                    throw t
                }
                steps[index - 1] = steps[index - 1].copy(status = result.stepStatus(), output = result.describe())
                publish { it.copy(steps = steps.toList()) }
                return result
            }
        }

        val outcome = ObjectiveEngine(
            registry = registry,
            executor = recording,
            stateMachine = machine,
            planner = planner,
            maxIterations = maxIterations,
            reservedFinalizationIterations = reservedFinalizationIterations,
        ).run(objective, isCancelled = isCancelled)

        val (status, summary) = when (val final = outcome.finalState) {
            is AgentState.Completed -> PlanStatus.COMPLETED to final.summary
            is AgentState.Failed -> PlanStatus.FAILED to (final.cause.message ?: final.cause.javaClass.simpleName)
            is AgentState.Cancelled -> PlanStatus.CANCELLED to final.reason
            else ->
                // ObjectiveEngine returns the machine's live state when a tool was cancelled mid-run.
                if (isCancelled()) PlanStatus.CANCELLED to "Cancelled" else PlanStatus.FAILED to "Ended in state $final"
        }
        publish { it.copy(status = status, summary = summary, finishedAt = now()) }
        return snapshot
    }

    private fun ToolResult.stepStatus(): PlanStepStatus = when (this) {
        is ToolResult.Success -> PlanStepStatus.SUCCEEDED
        is ToolResult.Partial -> PlanStepStatus.PARTIAL
        is ToolResult.Unexpected, is ToolResult.Failure -> PlanStepStatus.FAILED
    }
}
