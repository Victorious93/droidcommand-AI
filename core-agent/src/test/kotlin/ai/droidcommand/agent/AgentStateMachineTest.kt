package ai.droidcommand.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `pause captures the running state and resume restores it exactly`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.ExecutingTool("build", 2))

        val paused = assertIs<AgentState.Paused>(machine.pause())
        assertEquals(AgentState.ExecutingTool("build", 2), paused.resumeState)
        assertEquals(paused, machine.state)

        val resumed = machine.resume()
        assertEquals(AgentState.ExecutingTool("build", 2), resumed)
        assertEquals(AgentState.ExecutingTool("build", 2), machine.state)
    }

    @Test
    fun `a paused task can still be cancelled`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.ExecutingTool("noop", 1))
        machine.pause()
        val cancelled = machine.transition(AgentState.Cancelled("user cancelled while paused"))
        assertEquals(AgentState.Cancelled("user cancelled while paused"), cancelled)
    }

    @Test
    fun `rejects pausing a terminal task`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.Completed("done"))
        assertFailsWith<IllegalAgentTransition> { machine.pause() }
    }

    @Test
    fun `rejects pausing an already-paused task`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.Planning("obj"))
        machine.pause()
        assertFailsWith<IllegalAgentTransition> { machine.pause() }
    }

    @Test
    fun `rejects resuming a task that is not paused`() {
        val machine = AgentStateMachine()
        machine.transition(AgentState.ExecutingTool("noop", 1))
        assertFailsWith<IllegalStateException> { machine.resume() }
    }

    @Test
    fun `a value written by transition on one thread is visible when read on another`() {
        val machine = AgentStateMachine()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { machine.transition(AgentState.Planning("from another thread")) }.get(5, TimeUnit.SECONDS)
            assertEquals(AgentState.Planning("from another thread"), machine.state)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `concurrent transitions to a terminal state never let more than one succeed`() {
        val machine = AgentStateMachine()
        val threadCount = 16
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        val rejections = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(threadCount)

        try {
            val futures = (1..threadCount).map { i ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    try {
                        machine.transition(AgentState.Completed("done-$i"))
                        successes.incrementAndGet()
                    } catch (e: IllegalAgentTransition) {
                        rejections.incrementAndGet()
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
        }

        assertEquals(1, successes.get(), "exactly one concurrent transition to a terminal state should win")
        assertEquals(threadCount - 1, rejections.get())
        assertIs<AgentState.Completed>(machine.state)
    }
}
