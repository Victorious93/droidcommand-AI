package ai.droidcommand.security

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.DroidCommandSession
import ai.droidcommand.agent.Planner
import ai.droidcommand.agent.PlannerDecision
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class SessionCountingTool(override val spec: ToolSpec) : Tool {
    var invocations = 0
        private set

    override fun execute(input: Map<String, String>): ToolResult {
        invocations++
        return ToolResult.Success("ran")
    }
}

/**
 * Proves the [ai.droidcommand.agent.ToolRunner] seam this session's slice added actually works
 * end to end: a real [DroidCommandSession] driven entirely by a real [SecureToolExecutor], not
 * the plain [ToolExecutor] every other `DroidCommandSession`/`ObjectiveEngine` test uses. No
 * `core-agent` code changed to make this possible — only its `executor` field's declared type
 * did, per this addendum's own design.
 */
class DroidCommandSessionSecureToolExecutorIntegrationTest {
    private fun newSecureSession(
        tool: SessionCountingTool,
        policy: SecurityPolicy,
        approvalPrompt: ApprovalPrompt,
    ): DroidCommandSession {
        val registry = ToolRegistry().apply { register(tool) }
        val stateMachine = AgentStateMachine()
        val delegate = ToolExecutor(registry, stateMachine, sleep = { })
        val secure = SecureToolExecutor(registry, delegate, stateMachine, SecurityPolicyEnforcer(policy), approvalPrompt)
        return DroidCommandSession(registry, secure, stateMachine)
    }

    @Test
    fun `a Pilot instruction for a SENSITIVE tool succeeds once approval is granted`() {
        val tool = SessionCountingTool(ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE))
        val session = newSecureSession(tool, SecurityPolicy(), ApprovalPrompt { true })

        val result = session.runPilotInstruction("delete-app", emptyMap())

        assertIs<ToolResult.Success>(result)
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `a Pilot instruction for a SENSITIVE tool is denied when approval is refused, and never invokes it`() {
        val tool = SessionCountingTool(ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE))
        val session = newSecureSession(tool, SecurityPolicy(), ApprovalPrompt { false })

        val result = session.runPilotInstruction("delete-app", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `a Forge-only tool is denied via Pilot even though approval would have said yes`() {
        val tool = SessionCountingTool(
            ToolSpec(
                name = "forge-only",
                description = "d",
                securityLevel = SecurityLevel.SENSITIVE,
                allowedModes = setOf(AgentMode.FORGE),
            ),
        )
        val session = newSecureSession(tool, SecurityPolicy(), ApprovalPrompt { true })

        val result = session.runPilotInstruction("forge-only", emptyMap())

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, tool.invocations)
    }

    @Test
    fun `a Forge objective drives a SENSITIVE tool through SecureToolExecutor to completion`() {
        val tool = SessionCountingTool(ToolSpec(name = "delete-app", description = "d", securityLevel = SecurityLevel.SENSITIVE))
        val session = newSecureSession(tool, SecurityPolicy(), ApprovalPrompt { true })
        session.switchMode(AgentMode.FORGE)

        val planner = object : Planner {
            var calls = 0
            override fun decide(
                objective: String,
                context: ConversationContext,
                availableTools: List<ToolSpec>,
                lastObservation: ToolResult?,
            ): PlannerDecision {
                calls++
                return if (calls == 1) {
                    PlannerDecision.InvokeTool("delete-app", emptyMap())
                } else {
                    PlannerDecision.Complete("done")
                }
            }
        }

        val outcome = session.runForgeObjective("do something", planner)

        assertIs<AgentState.Completed>(outcome.finalState)
        assertEquals(1, tool.invocations)
    }
}
