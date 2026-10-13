package ai.droidcommand.app.engine

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.DroidCommandSession
import ai.droidcommand.agent.PilotInstruction
import ai.droidcommand.agent.PilotParse
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.describe
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import javax.inject.Inject

/**
 * Runs one Pilot instruction on a fresh state machine, through the same approval-gated executor
 * the Tools screens use. A new machine is required because a finished task cannot be resumed.
 */
class PilotDispatcher @Inject constructor(
    private val registry: ToolRegistry,
    private val policy: SecurityPolicy,
    private val approvalPrompt: ApprovalPrompt,
    private val auditLog: JsonFileAuditLog,
) {
    fun pilotToolNames(): String =
        registry.list(AgentMode.PILOT).joinToString(", ") { it.name }.ifEmpty { "(none registered)" }

    fun run(instruction: String): String {
        val parsed = when (val result = PilotInstruction.parse(instruction)) {
            is PilotParse.Invalid -> return result.message
            is PilotParse.Ok -> result
        }
        val machine = AgentStateMachine()
        val runner = SecureToolExecutor(
            registry,
            ToolExecutor(registry, machine),
            machine,
            SecurityPolicyEnforcer(policy),
            approvalPrompt,
            auditLog = auditLog,
        )
        val session = DroidCommandSession(registry, runner, machine)
        return try {
            session.runPilotInstruction(parsed.toolName, parsed.input).describe()
        } catch (e: Exception) {
            "Pilot did not run: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
