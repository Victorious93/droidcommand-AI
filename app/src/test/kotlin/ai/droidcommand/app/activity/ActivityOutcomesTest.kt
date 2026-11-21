package ai.droidcommand.app.activity

import ai.droidcommand.activity.ActivityActor
import ai.droidcommand.activity.ActivityKind
import ai.droidcommand.activity.ActivityRun
import ai.droidcommand.activity.InMemoryActivityLog
import ai.droidcommand.agent.PlanRun
import ai.droidcommand.agent.PlanStatus
import ai.droidcommand.agent.ToolResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityOutcomesTest {
    private val log = InMemoryActivityLog()

    private fun run() = ActivityRun.start(ActivityActor("dc:pilot", "Pilot"), "Pilot: echo", log)

    private fun last() = log.snapshot().last()

    private fun plan(status: PlanStatus, summary: String? = null) =
        PlanRun("p1", "an objective", status, emptyList(), summary, Instant.EPOCH)

    @Test
    fun `success and partial results end done, and say when it was only partly done`() {
        run().endFromResult("echo", ToolResult.Success("hi"), stopped = false)
        assertEquals(ActivityKind.RUN_FINISHED, last().kind)
        assertEquals("Done: echo", last().summary)

        run().endFromResult("copy", ToolResult.Partial("3 of 5", "disk full"), stopped = false)
        assertEquals(ActivityKind.RUN_FINISHED, last().kind)
        assertTrue("disk full" in last().summary)
    }

    @Test
    fun `failure and unexpected results end failed with the reason`() {
        run().endFromResult("echo", ToolResult.Failure("denied by policy"), stopped = false)
        assertEquals(ActivityKind.RUN_FAILED, last().kind)
        assertTrue("denied by policy" in last().summary)

        run().endFromResult("echo", ToolResult.Unexpected("odd state"), stopped = false)
        assertEquals(ActivityKind.RUN_FAILED, last().kind)
    }

    @Test
    fun `a stopped run is shown as stopped whatever the tool returned`() {
        run().endFromResult("echo", ToolResult.Success("finished anyway"), stopped = true)
        assertEquals(ActivityKind.RUN_CANCELLED, last().kind)
    }

    @Test
    fun `a plan ends from the status the runner reports`() {
        run().endFromPlan(plan(PlanStatus.COMPLETED, "All done"))
        assertEquals(ActivityKind.RUN_FINISHED, last().kind)
        assertEquals("All done", last().summary)

        run().endFromPlan(plan(PlanStatus.FAILED))
        assertEquals(ActivityKind.RUN_FAILED, last().kind)
        assertEquals("Plan failed", last().summary)

        run().endFromPlan(plan(PlanStatus.CANCELLED))
        assertEquals(ActivityKind.RUN_CANCELLED, last().kind)
    }

    @Test
    fun `a plan still reported as running does not end the row`() {
        val run = run()
        run.endFromPlan(plan(PlanStatus.RUNNING))
        assertFalse(run.isEnded)
    }
}
