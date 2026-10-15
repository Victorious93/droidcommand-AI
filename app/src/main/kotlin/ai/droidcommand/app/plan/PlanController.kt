package ai.droidcommand.app.plan

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.PlanHistoryFile
import ai.droidcommand.agent.PlanRun
import ai.droidcommand.agent.PlanRunner
import ai.droidcommand.agent.PlanStatus
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.app.mode.EngineModes
import ai.droidcommand.config.EncryptedSecretsVault
import ai.droidcommand.llm.factory.CloudPlannerFactory
import ai.droidcommand.llm.factory.CloudProviderSpec
import ai.droidcommand.llm.factory.PlannerResult
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [runs] is newest first; the first entry is the in-flight run while [running] is true, otherwise the
 * most recent finished one. Finished runs are reloaded from disk — see [PlanController].
 */
data class PlanUiState(
    val runs: List<PlanRun> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * App-lifetime owner of Plan runs, so an objective keeps running while the user switches tabs. One run
 * at a time. Each run gets its OWN state machine and [SecureToolExecutor] (via [PlanRunner]), because a
 * finished objective leaves a state machine permanently terminal and the app-wide one backs the Tools
 * screens. Tools still go through the same policy, approval prompt and audit log as everywhere else:
 * a run can never invoke a SENSITIVE/ROOT tool without the user's approval, and tools with no real
 * backend configured (most of them today) are denied, which the model sees as a failed step.
 *
 * Finished runs are written to [PlanHistoryFile] and reloaded when the process starts again. A run that
 * is still going when the process ends is not restored. Capped at [PlanHistoryFile.MAX_RUNS].
 * Never run on a device; the LLM planner's real behaviour with these tools is unobserved.
 */
@Singleton
class PlanController @Inject constructor(
    @ApplicationContext context: Context,
    private val registry: ToolRegistry,
    private val policy: SecurityPolicy,
    private val approvalPrompt: ApprovalPrompt,
    private val auditLog: JsonFileAuditLog,
    private val vault: EncryptedSecretsVault,
    private val transport: HttpTransport,
    private val modes: EngineModes,
) {
    private val history = PlanHistoryFile(context.filesDir.toPath().resolve("plans").resolve("history.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(PlanUiState(runs = runCatching { history.load() }.getOrDefault(emptyList())))
    val state: StateFlow<PlanUiState> = mutableState

    fun start(spec: CloudProviderSpec, model: String, objective: String) {
        val text = objective.trim()
        if (text.isEmpty()) return
        // Forge only. tryBegin also refuses a second run, so a double tap cannot start two objectives.
        if (!modes.tryBegin(AgentMode.FORGE)) {
            mutableState.update { it.copy(error = modes.state.value.switchError) }
            return
        }
        cancelled.set(false)
        mutableState.update { it.copy(running = true, error = null) }

        scope.launch {
            try {
                val planner = when (val result = CloudPlannerFactory.create(vault, spec, model, transport)) {
                    is PlannerResult.MissingKey -> {
                        mutableState.update { it.copy(error = result.message) }
                        return@launch
                    }
                    is PlannerResult.Ready -> result.planner
                }
                val runner = PlanRunner(
                    registry = registry,
                    planner = planner,
                    newRunner = { machine ->
                        SecureToolExecutor(
                            registry,
                            ToolExecutor(registry, machine),
                            machine,
                            SecurityPolicyEnforcer(policy),
                            approvalPrompt,
                            auditLog = auditLog,
                        )
                    },
                )
                runner.run(text, isCancelled = { cancelled.get() }, onUpdate = ::publish)
            } catch (e: Exception) {
                // Exception, not Throwable: an Error (e.g. OutOfMemoryError) should not be swallowed.
                // The message is the failure's own; the API key is only ever read lazily by the provider.
                mutableState.update { it.copy(error = "Plan run failed: ${e.message ?: e.javaClass.simpleName}") }
            } finally {
                mutableState.update { it.copy(running = false) }
                modes.endTask()
            }
        }
    }

    /** Asks the current run to stop before its next step. A tool already waiting on an approval dialog still needs that dialog answered. */
    fun stop() {
        cancelled.set(true)
    }

    /** Removes a finished run from history; a running one cannot be removed. */
    fun delete(id: String) {
        val next = mutableState.updateAndGet { state ->
            state.copy(runs = state.runs.filterNot { it.id == id && it.status != PlanStatus.RUNNING })
        }
        runCatching { history.save(next.runs) }
    }

    private fun publish(run: PlanRun) {
        val next = mutableState.updateAndGet { state ->
            val rest = state.runs.filterNot { it.id == run.id }
            state.copy(runs = (listOf(run) + rest).take(PlanHistoryFile.MAX_RUNS))
        }
        if (run.status != PlanStatus.RUNNING) runCatching { history.save(next.runs) }
    }
}
