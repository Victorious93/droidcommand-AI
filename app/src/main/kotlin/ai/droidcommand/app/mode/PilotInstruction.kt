package ai.droidcommand.app.mode

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.DroidCommandSession
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner

/**
 * One Pilot instruction through a fresh [DroidCommandSession].
 *
 * The session is created per call, in [AgentMode.PILOT], with a new [AgentStateMachine]: a machine is
 * terminal once a task ends, so it cannot be kept for the next instruction. [newRunner] must bind its
 * executor to the machine it is given — the same rule [ai.droidcommand.agent.PlanRunner] uses for Forge.
 */
class PilotInstruction(
    private val registry: ToolRegistry,
    private val newRunner: (AgentStateMachine) -> ToolRunner,
) {
    fun run(toolName: String, input: Map<String, String>, isCancelled: () -> Boolean = { false }): ToolResult {
        val machine = AgentStateMachine()
        val session = DroidCommandSession(registry, newRunner(machine), machine)
        session.switchMode(AgentMode.PILOT)
        return session.runPilotInstruction(toolName, input, isCancelled = isCancelled)
    }
}
