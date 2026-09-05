package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class SessionNoopTool(name: String) : Tool {
    override val spec = ToolSpec(name = name, description = "no-op")
    override fun execute(input: Map<String, String>) = ToolResult.Success("noop")
}

private class ImmediateCompletePlanner : Planner {
    override fun decide(
        objective: String,
        context: ConversationContext,
        availableTools: List<ToolSpec>,
        lastObservation: ToolResult?,
    ) = PlannerDecision.Complete("done")
}

private fun newSession(): Triple<DroidCommandSession, ToolRegistry, AgentStateMachine> {
    val registry = ToolRegistry().apply { register(SessionNoopTool("echo")) }
    val stateMachine = AgentStateMachine()
    val executor = ToolExecutor(registry, stateMachine, sleep = { })
    return Triple(DroidCommandSession(registry, executor, stateMachine), registry, stateMachine)
}

class DroidCommandSessionTest {
    @Test
    fun `starts in Pilot mode`() {
        val (session, _, _) = newSession()
        assertEquals(AgentMode.PILOT, session.mode)
    }

    @Test
    fun `runs a pilot instruction while in Pilot mode`() {
        val (session, _, _) = newSession()
        val result = session.runPilotInstruction("echo", emptyMap())
        assertIs<ToolResult.Success>(result)
    }

    @Test
    fun `rejects running a pilot instruction while in Forge mode`() {
        val (session, _, _) = newSession()
        session.switchMode(AgentMode.FORGE)
        assertFailsWith<IllegalStateException> {
            session.runPilotInstruction("echo", emptyMap())
        }
    }

    @Test
    fun `runs a forge objective while in Forge mode`() {
        val (session, _, _) = newSession()
        session.switchMode(AgentMode.FORGE)
        val outcome = session.runForgeObjective("do something", ImmediateCompletePlanner())
        assertIs<AgentState.Completed>(outcome.finalState)
    }

    @Test
    fun `rejects running a forge objective while in Pilot mode`() {
        val (session, _, _) = newSession()
        assertFailsWith<IllegalStateException> {
            session.runForgeObjective("do something", ImmediateCompletePlanner())
        }
    }

    @Test
    fun `switches Pilot to Forge and back to Pilot while idle`() {
        val (session, _, _) = newSession()
        session.switchMode(AgentMode.FORGE)
        assertEquals(AgentMode.FORGE, session.mode)
        session.switchMode(AgentMode.PILOT)
        assertEquals(AgentMode.PILOT, session.mode)
    }

    @Test
    fun `rejects a mode switch attempted while a Pilot task is active`() {
        val (session, _, _) = newSession()
        var attemptedSwitchFailed = false

        session.runPilotInstruction(
            "echo",
            emptyMap(),
            isCancelled = {
                try {
                    session.switchMode(AgentMode.FORGE)
                } catch (e: IllegalModeSwitch) {
                    attemptedSwitchFailed = true
                }
                false
            },
        )

        assertEquals(true, attemptedSwitchFailed)
        assertEquals(AgentMode.PILOT, session.mode) // switch never actually applied
    }

    @Test
    fun `rejects a mode switch attempted mid-objective-loop, then allows it once the loop ends`() {
        val (session, registry, _) = newSession()
        session.switchMode(AgentMode.FORGE)
        var attemptedSwitchFailed = false

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
                    PlannerDecision.InvokeTool("echo", emptyMap())
                } else {
                    PlannerDecision.Complete("done")
                }
            }
        }

        session.runForgeObjective(
            "objective",
            planner,
            isCancelled = {
                if (!attemptedSwitchFailed) {
                    try {
                        session.switchMode(AgentMode.PILOT)
                    } catch (e: IllegalModeSwitch) {
                        attemptedSwitchFailed = true
                    }
                }
                false
            },
        )

        assertEquals(true, attemptedSwitchFailed)
        // The task is over now, so the switch that was blocked mid-flight can succeed afterward.
        session.switchMode(AgentMode.PILOT)
        assertEquals(AgentMode.PILOT, session.mode)
    }
}
