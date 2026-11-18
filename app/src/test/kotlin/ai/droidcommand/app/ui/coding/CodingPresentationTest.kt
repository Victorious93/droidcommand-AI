package ai.droidcommand.app.ui.coding

import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.coding.CodingPhase
import ai.droidcommand.coding.CodingSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CodingPresentationTest {
    private fun session(phase: CodingPhase, edit: String = "", report: String = "") =
        CodingSession(id = "s", goal = "g", phase = phase, plan = "the plan", edit = edit, report = report)

    private fun marks(s: CodingSession) = CodingPresentation.steps(s).map { it.mark }

    @Test
    fun `there are always three steps in plan, edit, report order`() {
        for (phase in CodingPhase.entries) {
            assertEquals(listOf("Plan", "Edit", "Report"), CodingPresentation.steps(session(phase)).map { it.label })
        }
    }

    @Test
    fun `a running session shows what is done, what is current and what is left`() {
        assertEquals(listOf(StepMark.CURRENT, StepMark.TODO, StepMark.TODO), marks(session(CodingPhase.PLAN)))
        assertEquals(listOf(StepMark.DONE, StepMark.CURRENT, StepMark.TODO), marks(session(CodingPhase.EDIT, edit = "e")))
        assertEquals(listOf(StepMark.DONE, StepMark.DONE, StepMark.DONE), marks(session(CodingPhase.REPORT, "e", "r")))
    }

    @Test
    fun `a cancelled session marks the step it stopped in`() {
        assertEquals(listOf(StepMark.STOPPED, StepMark.TODO, StepMark.TODO), marks(session(CodingPhase.CANCELLED, report = "c")))
        assertEquals(listOf(StepMark.DONE, StepMark.STOPPED, StepMark.TODO), marks(session(CodingPhase.CANCELLED, edit = "e", report = "c")))
    }

    @Test
    fun `only a finished session is shown as done, and only a cancelled one as a warning`() {
        assertEquals("Done" to PillTone.Success, CodingPresentation.phaseLabel(CodingPhase.REPORT))
        assertEquals("Cancelled" to PillTone.Warning, CodingPresentation.phaseLabel(CodingPhase.CANCELLED))
        assertEquals(PillTone.Neutral, CodingPresentation.phaseLabel(CodingPhase.PLAN).second)
        assertEquals(PillTone.Neutral, CodingPresentation.phaseLabel(CodingPhase.EDIT).second)
    }

    @Test
    fun `the continue button exists only while there is a next step`() {
        assertEquals("Continue to edit", CodingPresentation.continueLabel(CodingPhase.PLAN))
        assertEquals("Continue to report", CodingPresentation.continueLabel(CodingPhase.EDIT))
        assertNull(CodingPresentation.continueLabel(CodingPhase.REPORT))
        assertNull(CodingPresentation.continueLabel(CodingPhase.CANCELLED))
    }

    @Test
    fun `the newest text is the one shown open`() {
        assertEquals("Plan" to "the plan", CodingPresentation.latestTitleAndBody(session(CodingPhase.PLAN)))
        assertEquals("Edit" to "e", CodingPresentation.latestTitleAndBody(session(CodingPhase.EDIT, edit = "e")))
        assertEquals("Report" to "r", CodingPresentation.latestTitleAndBody(session(CodingPhase.REPORT, "e", "r")))
        assertEquals("Report" to "c", CodingPresentation.latestTitleAndBody(session(CodingPhase.CANCELLED, report = "c")))
    }

    @Test
    fun `earlier steps are the ones that were not shown open`() {
        assertEquals(emptyList(), CodingPresentation.earlier(session(CodingPhase.PLAN)))
        assertEquals(listOf("Plan" to "the plan"), CodingPresentation.earlier(session(CodingPhase.EDIT, edit = "e")))
        assertEquals(
            listOf("Plan" to "the plan", "Edit" to "e"),
            CodingPresentation.earlier(session(CodingPhase.REPORT, "e", "r")),
        )
        // Cancelled before any edit: the report is open and the plan is the one earlier step.
        assertEquals(listOf("Plan" to "the plan"), CodingPresentation.earlier(session(CodingPhase.CANCELLED, report = "c")))
        assertNotNull(CodingPresentation.earlier(session(CodingPhase.CANCELLED, report = "c")))
    }
}
