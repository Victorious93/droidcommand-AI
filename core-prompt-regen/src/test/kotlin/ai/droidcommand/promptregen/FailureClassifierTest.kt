package ai.droidcommand.promptregen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailureClassifierTest {
    @Test
    fun `the spec's own example refusal is classified as missing context`() {
        val result = FailureClassifier.classify("I don't have enough information to proceed.")
        assertEquals(FailureCategory.MISSING_CONTEXT, result?.category)
    }

    @Test
    fun `asking what task the user wants is repository-not-inspected`() {
        val result = FailureClassifier.classify("What task do you want me to do?")
        assertEquals(FailureCategory.REPOSITORY_NOT_INSPECTED, result?.category)
    }

    @Test
    fun `a genuinely fine response is not classified as any failure`() {
        val result = FailureClassifier.classify(
            "Implemented the caching layer in core-config/CacheLoader.kt, added 4 tests, ran " +
                "./gradlew test and confirmed 1021/1021 passing.",
        )
        assertNull(result)
    }

    @Test
    fun `ambiguous task phrasing is classified`() {
        val result = FailureClassifier.classify("Your request is too vague for me to act on.")
        assertEquals(FailureCategory.AMBIGUOUS_TASK, result?.category)
    }

    @Test
    fun `overly broad clarification is classified`() {
        val result = FailureClassifier.classify("What exactly do you want me to do?")
        assertEquals(FailureCategory.OVERLY_BROAD_CLARIFICATION, result?.category)
    }

    @Test
    fun `unsupported capability is classified`() {
        val result = FailureClassifier.classify("I cannot access that system from here.")
        assertEquals(FailureCategory.UNSUPPORTED_CAPABILITY, result?.category)
    }

    @Test
    fun `hallucinated requirement is classified`() {
        val result = FailureClassifier.classify("There is no such roadmap item in this repository.")
        assertEquals(FailureCategory.HALLUCINATED_REQUIREMENT, result?.category)
    }

    @Test
    fun `failure to verify is classified`() {
        val result = FailureClassifier.classify("I didn't actually run the tests, but the change should be correct.")
        assertEquals(FailureCategory.FAILURE_TO_VERIFY, result?.category)
    }

    @Test
    fun `incomplete implementation is classified`() {
        val result = FailureClassifier.classify("This implementation is partial and needs more work.")
        assertEquals(FailureCategory.INCOMPLETE_IMPLEMENTATION, result?.category)
    }

    @Test
    fun `premature conclusion is classified`() {
        val result = FailureClassifier.classify("This should now work correctly.")
        assertEquals(FailureCategory.PREMATURE_CONCLUSION, result?.category)
    }

    @Test
    fun `incorrect technical interpretation is classified`() {
        val result = FailureClassifier.classify("I misunderstood the request and built the wrong thing.")
        assertEquals(FailureCategory.INCORRECT_TECHNICAL_INTERPRETATION, result?.category)
    }

    @Test
    fun `incorrect assumption is classified`() {
        val result = FailureClassifier.classify("I assumed you meant the Android module, not the CLI.")
        assertEquals(FailureCategory.INCORRECT_ASSUMPTION, result?.category)
    }

    @Test
    fun `refusal is classified`() {
        val result = FailureClassifier.classify("I'm sorry, but I can't help with that request.")
        assertEquals(FailureCategory.REFUSAL, result?.category)
    }

    @Test
    fun `evidence carries the exact matched substring`() {
        val result = FailureClassifier.classify("Sure, here it is. I don't have enough information to proceed.")
        assertEquals("i don't have enough information to proceed", result?.evidence?.lowercase())
    }
}
