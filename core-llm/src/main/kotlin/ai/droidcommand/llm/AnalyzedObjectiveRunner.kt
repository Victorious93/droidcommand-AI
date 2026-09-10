package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger
import ai.droidcommand.agent.TaskGraphExecutor
import ai.droidcommand.agent.TaskGraphOutcome
import ai.droidcommand.agent.TaskRunner

/** The outcome of [AnalyzedObjectiveRunner.run]. */
sealed class AnalyzedObjectiveOutcome {
    data class Executed(val analysis: ObjectiveAnalysisResult.Success, val taskGraphOutcome: TaskGraphOutcome) : AnalyzedObjectiveOutcome()

    /** [ObjectiveAnalyzer.analyze] itself did not produce a usable [TaskGraph][ai.droidcommand.agent.TaskGraph] — [runTask] was never invoked. */
    data class AnalysisFailed(val result: ObjectiveAnalysisResult) : AnalyzedObjectiveOutcome()
}

/**
 * Ties [ObjectiveAnalyzer] to [TaskGraphExecutor]: analyzes an objective,
 * then — only if analysis produced a valid [TaskGraph][ai.droidcommand.agent.TaskGraph] —
 * runs it. This is the ROADMAP-064/-067 counterpart to `core-agent`'s
 * `KnowledgeExtractionService`: the thin composition class that closes the
 * loop between an LLM-backed analysis stage and the engine that consumes
 * its output, so a caller doesn't have to hand-write the same
 * dispatch-on-sealed-result boilerplate every time.
 *
 * **A new, separate, caller-chosen entry point — not a replacement for
 * [ai.droidcommand.agent.ObjectiveEngine.run].** A caller picks this
 * *instead of* calling `ObjectiveEngine.run` directly with a raw objective
 * string; every existing `ObjectiveEngine.run` caller is completely
 * unaffected, since this class touches none of `core-agent`'s existing
 * files. See [TaskGraphExecutor]'s doc comment for the constraint that
 * matters when [runTask] is backed by a real `ObjectiveEngine`: a fresh
 * `AgentStateMachine` per task.
 */
class AnalyzedObjectiveRunner(
    private val analyzer: ObjectiveAnalyzer,
    private val taskGraphExecutor: TaskGraphExecutor = TaskGraphExecutor(),
    private val logger: Logger = NoOpLogger,
) {
    fun run(
        objective: String,
        runTask: TaskRunner,
        context: ConversationContext = ConversationContext(),
        isCancelled: () -> Boolean = { false },
    ): AnalyzedObjectiveOutcome {
        return when (val analysis = analyzer.analyze(objective, context)) {
            is ObjectiveAnalysisResult.Success -> {
                val taskGraphOutcome = taskGraphExecutor.run(analysis.taskGraph, runTask, isCancelled)
                AnalyzedObjectiveOutcome.Executed(analysis, taskGraphOutcome)
            }
            else -> {
                logger.warn("objective_analysis_failed", mapOf("result" to analysis::class.simpleName.orEmpty()))
                AnalyzedObjectiveOutcome.AnalysisFailed(analysis)
            }
        }
    }
}
