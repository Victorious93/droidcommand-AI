package ai.droidcommand.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PauseControllerTest {
    @Test
    fun `isPaused reflects pause and resume`() {
        val controller = PauseController()
        assertFalse(controller.isPaused())
        controller.pause()
        assertTrue(controller.isPaused())
        controller.resume()
        assertFalse(controller.isPaused())
    }

    @Test
    fun `awaitResume returns immediately when not paused`() {
        val controller = PauseController()
        // Would block up to maxWaitMillis if it ignored the not-paused fast path; assert it is prompt.
        val start = System.nanoTime()
        controller.awaitResume()
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMillis < 500, "awaitResume should return promptly when not paused (took ${elapsedMillis}ms)")
    }

    @Test
    fun `awaitResume blocks until another thread resumes, and is woken immediately`() {
        // A large maxWaitMillis so a pass cannot come from the timeout — only a real resume() can wake it.
        val controller = PauseController(maxWaitMillis = 60_000L)
        controller.pause()
        val resumed = AtomicBoolean(false)
        val waiterStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiter = executor.submit {
                waiterStarted.countDown()
                controller.awaitResume()
                resumed.set(true)
            }
            assertTrue(waiterStarted.await(5, TimeUnit.SECONDS))
            // Give the waiter a moment to actually enter the wait, then confirm it is still blocked.
            Thread.sleep(100)
            assertFalse(resumed.get(), "waiter must still be blocked before resume() is called")

            controller.resume()
            waiter.get(5, TimeUnit.SECONDS) // returns quickly if resume() woke it; times out the test if not
            assertTrue(resumed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a single awaitResume returns within the max wait even if never resumed, so cancellation can be observed`() {
        val controller = PauseController(maxWaitMillis = 150L)
        controller.pause()
        val start = System.nanoTime()
        controller.awaitResume() // never resumed — must return on the bound, not hang
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        assertTrue(controller.isPaused(), "still paused — only the bounded wait elapsed, not a resume")
        assertTrue(elapsedMillis in 100..5_000, "should return around the 150ms bound, took ${elapsedMillis}ms")
    }

    @Test
    fun `rejects a non-positive max wait`() {
        assertFailsWith<IllegalArgumentException> { PauseController(maxWaitMillis = 0) }
        assertFailsWith<IllegalArgumentException> { PauseController(maxWaitMillis = -1) }
    }

    @Test
    fun `drives ObjectiveEngine pause and resume end to end`() {
        val stateMachine = AgentStateMachine()
        val registry = ToolRegistry()
        val executor = ToolExecutor(registry, stateMachine, sleep = { })
        val planner = object : Planner {
            override fun decide(
                objective: String,
                context: ConversationContext,
                availableTools: List<ToolSpec>,
                lastObservation: ToolResult?,
            ): PlannerDecision = PlannerDecision.Complete("done")
        }
        val engine = ObjectiveEngine(registry, executor, stateMachine, planner)
        val controller = PauseController(maxWaitMillis = 60_000L)
        controller.pause()

        val runner = Executors.newSingleThreadExecutor()
        try {
            val future = runner.submit<ObjectiveOutcome> {
                engine.run("obj", isPaused = controller::isPaused, awaitResume = { controller.awaitResume() })
            }
            // While paused, the engine should be parked in AgentState.Paused, not completing.
            Thread.sleep(150)
            assertTrue(stateMachine.state is AgentState.Paused, "engine should be paused, was ${stateMachine.state}")
            assertFalse(future.isDone, "engine must not finish while paused")

            controller.resume()
            val outcome = future.get(5, TimeUnit.SECONDS)
            assertEquals(AgentState.Completed("done"), outcome.finalState)
            assertEquals(1, outcome.iterations)
        } finally {
            runner.shutdownNow()
        }
    }
}
