package ai.droidcommand.app.ui.tools.setoolkit

import ai.droidcommand.setoolkit.SetAttackVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetToolReviewTest {
    private val withHost = SetToolFormState(targetHost = "10.0.0.5")
    private val withEmail = SetToolFormState(targetEmail = "tester@example.com")

    @Test
    fun `every vector has a readable label with no underscores`() {
        for (vector in SetAttackVector.entries) {
            val label = SetToolReview.vectorLabel(vector)
            assertFalse(label.contains('_'), label)
            assertTrue(label.first().isUpperCase(), label)
        }
        assertEquals("Spear phishing", SetToolReview.vectorLabel(SetAttackVector.SPEAR_PHISHING))
    }

    @Test
    fun `a host or an email is a target, and neither is not`() {
        assertTrue(SetToolReview.hasTarget(withHost))
        assertTrue(SetToolReview.hasTarget(withEmail))
        assertFalse(SetToolReview.hasTarget(SetToolFormState()))
        assertFalse(SetToolReview.hasTarget(SetToolFormState(targetHost = "  ", targetEmail = "")))
    }

    @Test
    fun `launch needs a backend, a target and no run in progress`() {
        assertTrue(SetToolReview.canLaunch(withHost, backendAvailable = true, running = false))
        assertTrue(SetToolReview.canLaunch(withEmail, backendAvailable = true, running = false))
        assertFalse(SetToolReview.canLaunch(withHost, backendAvailable = false, running = false))
        assertFalse(SetToolReview.canLaunch(SetToolFormState(), backendAvailable = true, running = false))
        assertFalse(SetToolReview.canLaunch(withHost, backendAvailable = true, running = true))
    }

    @Test
    fun `with no backend the hint says so before it asks for a target`() {
        assertTrue(SetToolReview.launchHint(SetToolFormState(), backendAvailable = false)!!.contains("VictorSuite"))
        assertTrue(SetToolReview.launchHint(SetToolFormState(), backendAvailable = true)!!.contains("target"))
        assertNull(SetToolReview.launchHint(withHost, backendAvailable = true))
    }

    @Test
    fun `option keys follow the tool's own pattern`() {
        assertNull(SetToolReview.optionKeyProblem(""))
        assertNull(SetToolReview.optionKeyProblem("SMTP_SERVER"))
        assertNull(SetToolReview.optionKeyProblem("port2"))
        assertNotNull(SetToolReview.optionKeyProblem("bad key"))
        assertNotNull(SetToolReview.optionKeyProblem("bad-key"))
        assertNotNull(SetToolReview.optionKeyProblem("a=b"))
    }

    @Test
    fun `the summary lists only what was filled in and says when there is no target`() {
        val empty = SetToolReview.summary(SetToolFormState()).toMap()
        assertEquals("Spear phishing", empty["Attack vector"])
        assertEquals("not set", empty["Target"])
        val full = SetToolReview.summary(
            SetToolFormState(targetHost = "10.0.0.5", payload = "p", options = listOf("A" to "1", "" to "ignored")),
        ).toMap()
        assertEquals("10.0.0.5", full["Target host"])
        assertEquals("p", full["Payload"])
        assertEquals("A=1", full["Options"])
        assertFalse(full.containsKey("Target"))
    }

    @Test
    fun `the preview is just the main-menu digits for a fresh form`() {
        assertEquals(listOf("1", "1"), SetToolReview.stdinPreview(SetToolFormState(attackVector = SetAttackVector.SPEAR_PHISHING)))
        assertEquals(listOf("1", "2"), SetToolReview.stdinPreview(SetToolFormState(attackVector = SetAttackVector.WEBSITE_ATTACK)))
    }

    @Test
    fun `turning off the main menu leaves only the operator's own answers`() {
        val form = SetToolFormState(attackVector = SetAttackVector.WEBSITE_ATTACK, useMenuPath = false, menuAnswers = listOf("2", "10.0.0.5"))
        assertEquals(listOf("2", "10.0.0.5"), SetToolReview.stdinPreview(form))
    }

    @Test
    fun `menu answers are appended after the navigation digits, in order`() {
        val form = SetToolFormState(attackVector = SetAttackVector.WEBSITE_ATTACK, menuAnswers = listOf("10.0.0.5", "y"))
        assertEquals(listOf("1", "2", "10.0.0.5", "y"), SetToolReview.stdinPreview(form))
    }

    @Test
    fun `the summary always carries the preview, worded differently when it is empty`() {
        val empty = SetToolReview.summary(SetToolFormState(useMenuPath = false)).toMap()
        assertTrue(empty.getValue("Will type into SET, in order").contains("nothing"))
        val withAnswers = SetToolReview.summary(
            SetToolFormState(attackVector = SetAttackVector.WEBSITE_ATTACK, menuAnswers = listOf("10.0.0.5")),
        ).toMap()
        assertEquals("1 → 2 → 10.0.0.5", withAnswers.getValue("Will type into SET, in order"))
    }
}
