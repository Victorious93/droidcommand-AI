package ai.droidcommand.promptregen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RefusalDiagnosticsTest {
    private fun cat(status: Int?, body: String) = RefusalDiagnostics.diagnose(ProviderOutcome(status, body)).category

    @Test
    fun `transport and request failures are not mistaken for policy refusals`() {
        assertEquals(OutcomeCategory.AUTH_FAILURE, cat(401, """{"error":"invalid api key"}"""))
        assertEquals(OutcomeCategory.RATE_LIMITED, cat(429, "slow down"))
        assertEquals(OutcomeCategory.CONTEXT_OVERFLOW, cat(400, "prompt is too long: 210000 tokens > 200000 maximum"))
        assertEquals(OutcomeCategory.CONTEXT_OVERFLOW, cat(413, ""))
        assertEquals(OutcomeCategory.UNSUPPORTED_FEATURE, cat(400, "this model does not support tools"))
        assertEquals(OutcomeCategory.API_ERROR, cat(500, "internal error"))
    }

    @Test
    fun `a successful response containing a refusal is a policy refusal`() {
        val d = RefusalDiagnostics.diagnose(ProviderOutcome(200, "I'm sorry, but I can't help with that request."))
        assertEquals(OutcomeCategory.POLICY_REFUSAL, d.category)
        assertTrue(d.requiresUserApproval)
        assertTrue(d.evidence!!.contains("sorry", ignoreCase = true))
    }

    @Test
    fun `the policy-refusal suggestion never advises concealment or invented authorization`() {
        val s = RefusalDiagnostics.diagnose(ProviderOutcome(200, "I must decline.")).suggestion.lowercase()
        assertTrue("will not conceal intent" in s)
        assertFalse(listOf("rephrase to avoid", "bypass", "jailbreak", "pretend").any { it in s })
    }

    @Test
    fun `a clarification request is not a refusal`() {
        assertEquals(OutcomeCategory.NEEDS_CLARIFICATION, cat(200, "Could you clarify which module you want changed?"))
    }

    @Test
    fun `a normal response is success and needs no approval`() {
        val d = RefusalDiagnostics.diagnose(ProviderOutcome(200, "Done. Added 4 tests."))
        assertEquals(OutcomeCategory.SUCCESS, d.category)
        assertFalse(d.requiresUserApproval)
    }

    @Test
    fun `local provider with no status still classifies by body`() {
        assertEquals(OutcomeCategory.POLICY_REFUSAL, cat(null, "I cannot help with that."))
        assertEquals(OutcomeCategory.SUCCESS, cat(null, "ok"))
    }

    private val refusal = OutcomeDiagnosis(OutcomeCategory.POLICY_REFUSAL, "x", "s", true)

    @Test
    fun `retry requires user approval`() {
        val g = RetryGuard()
        g.recordAttempt("original prompt about module alpha")
        assertIs<RetryDecision.Blocked>(g.check("a materially different prompt entirely, with new details", refusal, userApproved = false))
        assertIs<RetryDecision.Allowed>(g.check("a materially different prompt entirely, with new details", refusal, userApproved = true))
    }

    @Test
    fun `an unchanged prompt is blocked after a refusal even when approved`() {
        val g = RetryGuard()
        g.recordAttempt("Please analyse the module alpha configuration file now")
        val d = g.check("please analyse the module alpha configuration file now!", refusal, userApproved = true)
        assertIs<RetryDecision.Blocked>(d)
        assertTrue("materially unchanged" in d.reason)
    }

    @Test
    fun `the attempt limit stops loops regardless of prompt changes`() {
        val g = RetryGuard(maxAttempts = 2)
        g.recordAttempt("one"); g.recordAttempt("two")
        val d = g.check("completely new third wording here", refusal, userApproved = true)
        assertEquals("attempt limit reached (2)", assertIs<RetryDecision.Blocked>(d).reason)
        g.reset()
        assertIs<RetryDecision.Allowed>(g.check("completely new third wording here", refusal, userApproved = true))
    }

    @Test
    fun `transient failures may resend the same prompt but still need approval and respect the limit`() {
        val g = RetryGuard(maxAttempts = 2)
        g.recordAttempt("same prompt text")
        val rate = RefusalDiagnostics.diagnose(ProviderOutcome(429, ""))
        assertIs<RetryDecision.Blocked>(g.check("same prompt text", rate, userApproved = false))
        assertIs<RetryDecision.Allowed>(g.check("same prompt text", rate, userApproved = true))
        g.recordAttempt("same prompt text")
        assertIs<RetryDecision.Blocked>(g.check("same prompt text", rate, userApproved = true))
    }
}
