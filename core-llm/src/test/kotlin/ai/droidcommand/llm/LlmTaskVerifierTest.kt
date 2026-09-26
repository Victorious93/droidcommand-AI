package ai.droidcommand.llm

import ai.droidcommand.agent.AgentState
import ai.droidcommand.agent.Message
import ai.droidcommand.agent.ObjectiveOutcome
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.Task
import ai.droidcommand.agent.TaskVerification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class VerifierFakeLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): LlmResponse {
        lastRequest = request
        return response
    }
}

class LlmTaskVerifierTest {
    private val task = Task(id = "a", description = "write the report", verificationCriteria = listOf("file exists", "has a title"))
    private val done = ObjectiveOutcome(AgentState.Completed("wrote report.md"), 2)

    @Test
    fun `every criterion met is Passed`() {
        val provider = VerifierFakeLlmProvider(
            LlmResponse.Text("""{"results":[{"criterion":"file exists","met":true},{"criterion":"has a title","met":true}]}"""),
        )

        assertEquals(TaskVerification.Passed, LlmTaskVerifier(provider).verify(task, done))
    }

    @Test
    fun `an unmet criterion is Failed with its reason`() {
        val provider = VerifierFakeLlmProvider(
            LlmResponse.Text(
                """{"results":[{"criterion":"file exists","met":true},{"criterion":"has a title","met":false,"reason":"no heading"}]}""",
            ),
        )

        val failed = assertIs<TaskVerification.Failed>(LlmTaskVerifier(provider).verify(task, done))
        assertEquals(listOf("has a title"), failed.unmetCriteria)
        assertTrue(failed.reason.contains("no heading"))
    }

    @Test
    fun `a judgment that skips a criterion is Inconclusive, not Passed`() {
        val provider = VerifierFakeLlmProvider(LlmResponse.Text("""{"results":[{"criterion":"file exists","met":true}]}"""))

        val inconclusive = assertIs<TaskVerification.Inconclusive>(LlmTaskVerifier(provider).verify(task, done))
        assertTrue(inconclusive.reason.contains("has a title"))
    }

    @Test
    fun `unparseable text is Inconclusive`() {
        val provider = VerifierFakeLlmProvider(LlmResponse.Text("looks good to me"))

        assertIs<TaskVerification.Inconclusive>(LlmTaskVerifier(provider).verify(task, done))
    }

    @Test
    fun `a provider error is Inconclusive`() {
        val provider = VerifierFakeLlmProvider(LlmResponse.Error(LlmError.ModelUnavailable("down")))

        assertIs<TaskVerification.Inconclusive>(LlmTaskVerifier(provider).verify(task, done))
    }

    @Test
    fun `a tool call is Inconclusive`() {
        val provider = VerifierFakeLlmProvider(LlmResponse.ToolCall("shell", emptyMap()))

        assertIs<TaskVerification.Inconclusive>(LlmTaskVerifier(provider).verify(task, done))
    }

    @Test
    fun `a task that did not complete never reaches the provider`() {
        val provider = VerifierFakeLlmProvider(LlmResponse.Text("{}"))
        val failed = ObjectiveOutcome(AgentState.Failed(RuntimeException("boom")), 1)

        assertIs<TaskVerification.Inconclusive>(LlmTaskVerifier(provider).verify(task, failed))
        assertNull(provider.lastRequest)
    }

    @Test
    fun `the request carries evidence, the summary, and every criterion`() {
        val provider = VerifierFakeLlmProvider(
            LlmResponse.Text("""{"results":[{"criterion":"file exists","met":true},{"criterion":"has a title","met":true}]}"""),
        )
        val evidence = listOf(Message(Role.TOOL, "wrote 120 bytes to report.md"))

        LlmTaskVerifier(provider) { evidence }.verify(task, done)

        val request = provider.lastRequest!!
        assertEquals(evidence.single(), request.messages.first())
        val prompt = request.messages.last().content
        assertTrue(prompt.contains("wrote report.md"))
        assertTrue(prompt.contains("- file exists") && prompt.contains("- has a title"))
        assertTrue(request.tools.isEmpty())
    }
}
