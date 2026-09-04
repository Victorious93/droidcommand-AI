package ai.droidforge.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentStateMachineTest {
    @Test
    fun `allows transition through a normal lifecycle`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.Planning("test objective"))
        machine.transition(AgentState.ExecutingTool("noop", 1))
        machine.transition(AgentState.Observing("noop", ToolResult.Success("ok")))
        val final = machine.transition(AgentState.Completed("done"))
        assertEquals(AgentState.Completed("done"), final)
    }

    @Test
    fun `rejects transition out of Completed`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.Completed("done"))
        assertFailsWith<IllegalAgentTransition> {
            machine.transition(AgentState.ExecutingTool("noop", 1))
        }
    }

    @Test
    fun `rejects transition out of Cancelled`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.Cancelled("user cancelled"))
        assertFailsWith<IllegalAgentTransition> {
            machine.transition(AgentState.Planning("new objective"))
        }
    }

    @Test
    fun `rejects transition out of Failed`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.Failed(RuntimeException("boom")))
        assertFailsWith<IllegalAgentTransition> {
            machine.transition(AgentState.Idle)
        }
    }
}
