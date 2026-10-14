package ai.droidcommand.llm.factory

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmProvider
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudPromptClientTest {
    private val spec = CloudProviderCatalog.byId("anthropic")!!
    private val key = "sk-test-0123456789"

    private val unusedTransport = object : HttpTransport {
        override fun send(request: HttpRequestSpec): HttpResponseSpec = error("the test supplies its own provider")
    }

    private class ScriptedProvider(private val reply: (LlmRequest) -> LlmResponse) : LlmProvider {
        override val config = LlmConfig(provider = "anthropic", model = "test-model", authToken = { null })
        val requests = mutableListOf<LlmRequest>()

        override fun complete(request: LlmRequest): LlmResponse {
            requests += request
            return reply(request)
        }
    }

    private fun vault(withKey: Boolean = true): InMemorySecretsVault = InMemorySecretsVault().also {
        if (withKey) it.putSecret(spec.secretId, key)
    }

    @Test
    fun `without a stored key nothing is built or sent`() {
        var built = false
        val client = CloudPromptClient(vault(withKey = false), unusedTransport, providerFor = { _, _ ->
            built = true
            ScriptedProvider { error("must not be called") }
        })
        val failure = assertIs<ChatResult.Failure>(client.ask(spec, "", "hello"))
        assertEquals("No API key for ${spec.label}. Add one in Settings.", failure.message)
        assertFalse(built)
    }

    @Test
    fun `a blank prompt is refused before anything is built`() {
        var built = false
        val client = CloudPromptClient(vault(), unusedTransport, providerFor = { _, _ ->
            built = true
            ScriptedProvider { error("must not be called") }
        })
        assertIs<ChatResult.Failure>(client.ask(spec, "", "   "))
        assertFalse(built)
    }

    @Test
    fun `the prompt is the only message sent, with no system prompt and no history`() {
        val provider = ScriptedProvider { LlmResponse.Text("pong") }
        val client = CloudPromptClient(vault(), unusedTransport, providerFor = { _, _ -> provider })
        assertEquals(ChatResult.Reply("pong"), client.ask(spec, "", "  ping  "))
        val request = provider.requests.single()
        assertNull(request.systemPrompt)
        assertEquals(listOf(Message(Role.USER, "ping")), request.messages)
    }

    @Test
    fun `an empty model falls back to the catalog suggestion`() {
        var modelSeen: String? = null
        val client = CloudPromptClient(vault(), unusedTransport, providerFor = { _, config ->
            modelSeen = config.model
            ScriptedProvider { LlmResponse.Text("ok") }
        })
        client.ask(spec, "   ", "hi")
        assertEquals(spec.defaultModel, modelSeen)
    }

    @Test
    fun `a provider error comes back with the key redacted`() {
        val provider = ScriptedProvider { LlmResponse.Error(LlmError.Authentication("rejected key $key")) }
        val client = CloudPromptClient(vault(), unusedTransport, providerFor = { _, _ -> provider })
        val message = assertIs<ChatResult.Failure>(client.ask(spec, "", "hi")).message
        assertFalse(message.contains(key))
        assertTrue(message.contains("[redacted]"))
    }

    @Test
    fun `an exception from the provider comes back with the key redacted`() {
        val provider = ScriptedProvider { throw IllegalStateException("connection failed while sending $key") }
        val client = CloudPromptClient(vault(), unusedTransport, providerFor = { _, _ -> provider })
        val message = assertIs<ChatResult.Failure>(client.ask(spec, "", "hi")).message
        assertTrue(message.startsWith("Request failed:"))
        assertFalse(message.contains(key))
    }

    @Test
    fun `an empty reply and a tool call are both reported as failures`() {
        val empty = CloudPromptClient(vault(), unusedTransport, providerFor = { _, _ -> ScriptedProvider { LlmResponse.Text("  ") } })
        assertEquals("The model returned an empty reply.", assertIs<ChatResult.Failure>(empty.ask(spec, "", "hi")).message)

        val tool = CloudPromptClient(vault(), unusedTransport, providerFor = { _, _ ->
            ScriptedProvider { LlmResponse.ToolCall("anything", emptyMap()) }
        })
        assertIs<ChatResult.Failure>(tool.ask(spec, "", "hi"))
    }
}
