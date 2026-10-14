package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.agent.describe
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class PilotUiState(
    val tools: List<ToolSpec> = emptyList(),
    val running: Boolean = false,
    val result: String? = null,
    val error: String? = null,
)

/**
 * Runs one Pilot instruction at a time through [PilotInstruction] and the same approval, policy and
 * audit gate the Tools screens use. The tool list is whatever the registry offers to Pilot as the
 * device owner — Forge-only tools are not shown and would be rejected by the session if named anyway.
 *
 * A run holds [EngineModes] busy until it returns, including while an approval dialog is open.
 */
@Singleton
class PilotController @Inject constructor(
    private val registry: ToolRegistry,
    private val policy: SecurityPolicy,
    private val approvalPrompt: ApprovalPrompt,
    private val auditLog: JsonFileAuditLog,
    private val modes: EngineModes,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(
        PilotUiState(tools = registry.list(AgentMode.PILOT, Initiator.DEVICE_OWNER)),
    )
    val state: StateFlow<PilotUiState> = mutableState

    fun run(toolName: String, argumentText: String) {
        if (mutableState.value.running) return
        val tool = mutableState.value.tools.firstOrNull { it.name == toolName }
        if (tool == null) {
            mutableState.update { it.copy(error = "Choose a tool first.") }
            return
        }
        val parsed = PilotArguments.parse(argumentText)
        if (parsed is PilotArguments.Invalid) {
            mutableState.update { it.copy(error = parsed.message, result = null) }
            return
        }
        val input = parsed.input
        if (!modes.tryBegin(AgentMode.PILOT)) {
            mutableState.update { it.copy(error = modes.state.value.switchError) }
            return
        }
        mutableState.update { it.copy(running = true, error = null, result = null) }
        scope.launch {
            try {
                val instruction = PilotInstruction(registry) { machine -> secureRunner(machine) }
                val result = instruction.run(tool.name, input)
                mutableState.update { it.copy(result = result.describe()) }
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                mutableState.update { it.copy(running = false) }
                modes.endTask()
            }
        }
    }

    private fun secureRunner(machine: AgentStateMachine): SecureToolExecutor {
        val delegate = ToolExecutor(registry, machine)
        return SecureToolExecutor(
            registry,
            delegate,
            machine,
            SecurityPolicyEnforcer(policy),
            approvalPrompt,
            auditLog = auditLog,
        )
    }
}
