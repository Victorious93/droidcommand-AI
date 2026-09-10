package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.TaskGraphError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class AnalyzerFakeLlmProvider(private val response: LlmResponse) : LlmProvider {
    override val config = LlmConfig(provider = "fake", model = "fake-1")
    var lastRequest: LlmRequest? = null

    override fun complete(request: LlmRequest): LlmResponse {
        lastRequest = request
        return response
    }
}

class ObjectiveAnalyzerTest {
    @Test
    fun `a valid multi-task object with dependencies parses into a correctly-ordered TaskGraph`() {
        val provider = AnalyzerFakeLlmProvider(
            LlmResponse.Text(
                """
                {"requirements":["req 1"],"constraints":["con 1"],"tasks":[
                  {"id":"a","description":"do a"},
                  {"id":"b","description":"do b","dependencies":["a"],"verificationCriteria":["b works"]}
                ]}
                """.trimIndent(),
            ),
        )

        val result = ObjectiveAnalyzer(provider).analyze("build a thing")

        val success = assertIs<ObjectiveAnalysisResult.Success>(result)
        assertEquals(listOf("req 1"), success.requirements)
        assertEquals(listOf("con 1"), success.constraints)
        assertEquals(listOf("a", "b"), success.taskGraph.executionOrder)
        assertEquals(listOf("b works"), success.taskGraph.task("b").verificationCriteria)
    }

    @Test
    fun `zero tasks is reported as InvalidTaskGraph, not Success`() {
        val provider = AnalyzerFakeLlmProvider(LlmResponse.Text("""{"requirements":[],"constraints":[],"tasks":[]}"""))

        val result = ObjectiveAnalyzer(provider).analyze("do nothing")

        val invalid = assertIs<ObjectiveAnalysisResult.InvalidTaskGraph>(result)
        assertEquals(listOf(TaskGraphError.EmptyTaskGraph), invalid.errors)
    }

    @Test
    fun `a cyclic dependency in the response is InvalidTaskGraph, never Success`() {
        val provider = AnalyzerFakeLlmProvider(
            LlmResponse.Text(
                """{"tasks":[{"id":"a","description":"a","dependencies":["b"]},{"id":"b","description":"b","dependencies":["a"]}]}""",
            ),
        )

        val result = ObjectiveAnalyzer(provider).analyze("build a thing")

        val invalid = assertIs<ObjectiveAnalysisResult.InvalidTaskGraph>(result)
        assertTrue(invalid.errors.single() is TaskGraphError.CyclicDependency)
    }

    @Test
    fun `an unknown dependency id is InvalidTaskGraph`() {
        val provider = AnalyzerFakeLlmProvider(
            LlmResponse.Text("""{"tasks":[{"id":"a","description":"a","dependencies":["missing"]}]}"""),
        )

        val result = ObjectiveAnalyzer(provider).analyze("build a thing")

        val invalid = assertIs<ObjectiveAnalysisResult.InvalidTaskGraph>(result)
        assertEquals(listOf(TaskGraphError.UnknownDependency("a", "missing")), invalid.errors)
    }

    @Test
    fun `malformed JSON text is reported, not thrown`() {
        val provider = AnalyzerFakeLlmProvider(LlmResponse.Text("not json at all"))

        val result = ObjectiveAnalyzer(provider).analyze("build a thing")

        val malformed = assertIs<ObjectiveAnalysisResult.Malformed>(result)
        assertEquals("not json at all", malformed.raw)
    }

    @Test
    fun `a provider error becomes ProviderFailed`() {
        val provider = AnalyzerFakeLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))

        val result = ObjectiveAnalyzer(provider).analyze("build a thing")

        val failed = assertIs<ObjectiveAnalysisResult.ProviderFailed>(result)
        assertEquals("invalid API key", failed.error.message)
    }

    @Test
    fun `an unexpected tool call is reported as Malformed instead of crashing`() {
        val provider = AnalyzerFakeLlmProvider(LlmResponse.ToolCall("some_tool", mapOf("x" to "1")))

        val result = ObjectiveAnalyzer(provider).analyze("build a thing")

        assertIs<ObjectiveAnalysisResult.Malformed>(result)
    }

    @Test
    fun `the request carries prior context messages plus the objective as a trailing user message, with no tools`() {
        val provider = AnalyzerFakeLlmProvider(LlmResponse.Text("""{"tasks":[{"id":"a","description":"a"}]}"""))
        val context = ConversationContext().apply { append(Role.USER, "earlier message") }

        ObjectiveAnalyzer(provider).analyze("build a thing", context)

        val request = provider.lastRequest!!
        assertEquals(
            listOf(Role.USER to "earlier message", Role.USER to "build a thing"),
            request.messages.map { it.role to it.content },
        )
        assertTrue(request.tools.isEmpty())
    }

    @Test
    fun `omitted dependencies and verificationCriteria default to empty`() {
        val provider = AnalyzerFakeLlmProvider(LlmResponse.Text("""{"tasks":[{"id":"a","description":"a"}]}"""))

        val result = ObjectiveAnalyzer(provider).analyze("build a thing")

        val success = assertIs<ObjectiveAnalysisResult.Success>(result)
        val task = success.taskGraph.task("a")
        assertEquals(emptySet(), task.dependencies)
        assertEquals(emptyList(), task.verificationCriteria)
    }
}
