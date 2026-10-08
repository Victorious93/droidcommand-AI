package ai.droidcommand.llm.factory

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.PlannerDecision
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.remote.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CloudPlannerFactoryTest {
    private val groq = CloudProviderCatalog.byId("groq")!!
    private val noTransport = object : HttpTransport {
        override fun send(request: ai.droidcommand.remote.HttpRequestSpec): ai.droidcommand.remote.HttpResponseSpec =
            error("the fake provider never touches the network")
    }

    private class FakeProvider(override val config: LlmConfig, private val response: LlmResponse) : LlmProvider {
        override fun complete(request: LlmRequest): LlmResponse = response
    }

    private fun decide(planner: ai.droidcommand.agent.Planner) =
        planner.decide("obj", ConversationContext(), emptyList(), null)

    @Test
    fun `missing key yields MissingKey naming the provider and never calling the provider factory`() {
        var built = false
        val result = CloudPlannerFactory.create(InMemorySecretsVault(), groq, "m", noTransport) { _, c -> built = true; FakeProvider(c, LlmResponse.Text("x")) }

        assertIs<PlannerResult.MissingKey>(result)
        assertEquals("No API key for ${groq.label}. Add one in Settings.", result.message)
        assertFalse(built)
    }

    @Test
    fun `blank key counts as missing`() {
        val vault = InMemorySecretsVault().apply { putSecret(groq.secretId, "   ") }

        assertIs<PlannerResult.MissingKey>(CloudPlannerFactory.create(vault, groq, "m", noTransport) { _, c -> FakeProvider(c, LlmResponse.Text("x")) })
    }

    @Test
    fun `a stored key yields a planner backed by the provider with the chosen model and the vault key`() {
        val vault = InMemorySecretsVault().apply { putSecret(groq.secretId, "sk-test") }
        var seen: LlmConfig? = null

        val result = CloudPlannerFactory.create(vault, groq, "  my-model ", noTransport) { _, c ->
            seen = c
            FakeProvider(c, LlmResponse.ToolCall("shell", mapOf("cmd" to "ls")))
        }

        assertIs<PlannerResult.Ready>(result)
        assertEquals("my-model", seen!!.model)
        assertEquals(groq.id, seen!!.provider)
        assertEquals("sk-test", seen!!.authToken())
        assertEquals(PlannerDecision.InvokeTool("shell", mapOf("cmd" to "ls")), decide(result.planner))
    }

    @Test
    fun `an empty model falls back to the provider default`() {
        val vault = InMemorySecretsVault().apply { putSecret(groq.secretId, "sk-test") }
        var seen: LlmConfig? = null

        CloudPlannerFactory.create(vault, groq, "  ", noTransport) { _, c -> seen = c; FakeProvider(c, LlmResponse.Text("x")) }

        assertEquals(groq.defaultModel, seen!!.model)
    }

    @Test
    fun `the key is read lazily so a key rotated after creation is picked up`() {
        val vault = InMemorySecretsVault().apply { putSecret(groq.secretId, "old") }
        var seen: LlmConfig? = null
        CloudPlannerFactory.create(vault, groq, "m", noTransport) { _, c -> seen = c; FakeProvider(c, LlmResponse.Text("x")) }

        vault.putSecret(groq.secretId, "new")

        assertEquals("new", seen!!.authToken())
    }

    @Test
    fun `a text reply from the model completes the objective`() {
        val vault = InMemorySecretsVault().apply { putSecret(groq.secretId, "sk-test") }

        val result = CloudPlannerFactory.create(vault, groq, "m", noTransport) { _, c -> FakeProvider(c, LlmResponse.Text("all done")) }

        assertEquals(PlannerDecision.Complete("all done"), decide((result as PlannerResult.Ready).planner))
    }
}
