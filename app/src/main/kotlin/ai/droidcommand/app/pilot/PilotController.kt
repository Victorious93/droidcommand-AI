package ai.droidcommand.app.pilot

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.DroidCommandSession
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.describe
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.JsonFileAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import javax.inject.Inject
import javax.inject.Singleton

data class PilotRunState(
    val tools: List<String> = emptyList(),
    val running: Boolean = false,
    val lastTool: String? = null,
    val lastResult: String? = null,
    val error: String? = null,
)

/**
 * Mode 1's engine call: one Pilot instruction. Each run gets its own [AgentStateMachine] and
 * [SecureToolExecutor], because a finished task leaves the machine terminal and the app-wide
 * executor backs the Tools screens. The session is constructed in [AgentMode.PILOT] (the default)
 * and only [DroidCommandSession.runPilotInstruction] is called — Forge is not used here.
 *
 * Tools still go through policy, the approval prompt and the audit log. A tool with no real backend
 * is denied, which comes back as the result text.
 */
@Singleton
class PilotController @Inject constructor(
    private val registry: ToolRegistry,
    private val policy: SecurityPolicy,
    private val approvalPrompt: ApprovalPrompt,
    private val auditLog: JsonFileAuditLog,
) {
    fun toolNames(): List<String> =
        registry.list(AgentMode.PILOT, Initiator.DEVICE_OWNER).map { it.name }.sorted()

    fun run(toolName: String, input: Map<String, String>): String {
        if (toolName !in toolNames()) return "Unknown tool."
        val machine = AgentStateMachine()
        val executor = SecureToolExecutor(
            registry,
            ToolExecutor(registry, machine),
            machine,
            SecurityPolicyEnforcer(policy),
            approvalPrompt,
            auditLog = auditLog,
        )
        val session = DroidCommandSession(registry, executor, machine)
        return session.runPilotInstruction(toolName, input).describe()
    }
}
