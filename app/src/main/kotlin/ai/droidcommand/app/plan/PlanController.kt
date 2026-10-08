package ai.droidcommand.app.plan

import ai.droidcommand.agent.PlanRun
import ai.droidcommand.agent.PlanRunner
import ai.droidcommand.agent.PlanStatus
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
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
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * [runs] is newest first; the first entry is the in-flight run while [running] is true, otherwise the
 * most recent finished one. In memory only — see [PlanController].
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
 * History is kept in memory for the life of the process and capped at [MAX_RUNS]; nothing is persisted.
 * Never run on a device; the LLM planner's real behaviour with these tools is unobserved.
 */
@Singleton
class PlanController @Inject constructor(
    private val registry: ToolRegistry,
    private val policy: SecurityPolicy,
    private val approvalPrompt: ApprovalPrompt,
    private val auditLog: JsonFileAuditLog,
    private val vault: EncryptedSecretsVault,
    private val transport: HttpTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = mutableState

    fun start(spec: CloudProviderSpec, model: String, objective: String) {
        val text = objective.trim()
        if (text.isEmpty()) return
        // Claim the slot atomically; a second tap while one run is starting must not start another.
        // update{} may re-run its lambda on contention, so the flag is reassigned on every attempt.
        val claimed = AtomicBoolean(false)
        mutableState.update {
            claimed.set(!it.running)
            if (it.running) it else it.copy(running = true, error = null)
        }
        if (!claimed.get()) return
        cancelled.set(false)

        scope.launch {
            try {
                val planner = when (val result = CloudPlannerFactory.create(vault, spec, model, transport)) {
                    is PlannerResult.MissingKey -> {
                        mutableState.update { it.copy(running = false, error = result.message) }
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
                mutableState.update { it.copy(running = false) }
            } catch (e: Exception) {
                // Exception, not Throwable: an Error (e.g. OutOfMemoryError) should not be swallowed.
                // The message is the failure's own; the API key is only ever read lazily by the provider.
                mutableState.update { it.copy(running = false, error = "Plan run failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Asks the current run to stop before its next step. A tool already waiting on an approval dialog still needs that dialog answered. */
    fun stop() {
        cancelled.set(true)
    }

    /** Removes a finished run from history; a running one cannot be removed. */
    fun delete(id: String) {
        mutableState.update { state -> state.copy(runs = state.runs.filterNot { it.id == id && it.status != PlanStatus.RUNNING }) }
    }

    private fun publish(run: PlanRun) {
        mutableState.update { state ->
            val rest = state.runs.filterNot { it.id == run.id }
            state.copy(runs = (listOf(run) + rest).take(MAX_RUNS))
        }
    }

    private companion object {
        const val MAX_RUNS = 20
    }
}
