package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.agent.InMemoryPersonaStore
import ai.droidcommand.agent.LogEvent
import ai.droidcommand.agent.LogLevel
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingManagerLogger : Logger {
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        events.add(event)
    }
}

private class ScriptedManagerLlmProvider(private val responses: MutableList<LlmResponse>) : LlmProvider {
    constructor(vararg responses: LlmResponse) : this(responses.toMutableList())

    override val config = LlmConfig(provider = "fake", model = "fake-1")
    val requests = mutableListOf<LlmRequest>()

    override fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return if (responses.size > 1) responses.removeAt(0) else responses.first()
    }
}

private const val VALID_STYLE_JSON = """{
    "tone": "warm", "vocabularyComplexity": "simple", "notableWords": [],
    "typicalSentenceLength": "short", "punctuationHabits": "minimal",
    "formality": "CASUAL", "verbosity": "CONCISE",
    "humorPresent": false, "humorStyle": null,
    "responseStructure": "bullet points", "commonExpressions": [],
    "contextContribution": "Write warmly and concisely."
}"""

class DefaultPersonaManagerTest {
    private fun conversationStore(vararg ids: String): InMemoryConversationStore {
        val store = InMemoryConversationStore()
        ids.forEach { id -> store.save(id, ConversationContext().apply { append(Role.USER, "hi from $id") }) }
        return store
    }

    @Test
    fun `a successful creation saves to the store with sourceConversations reflecting only resolved ids`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val personaStore = InMemoryPersonaStore()
        val conversationStore = conversationStore("chat-1")
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), personaStore, conversationStore, provider)

        val result = manager.createPersonaFromConversations(listOf("chat-1", "missing-chat"), "My Style", PersonaCategory.PERSONAL)

        val success = assertIs<PersonaExtractionResult.Success>(result)
        assertEquals(listOf("chat-1"), success.persona.sourceConversations)
        assertEquals(success.persona, personaStore.load(success.persona.id))
    }

    @Test
    fun `zero resolved ids returns NoSourceConversations and never calls the extractor`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val personaStore = InMemoryPersonaStore()
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), personaStore, InMemoryConversationStore(), provider)

        val result = manager.createPersonaFromConversations(listOf("missing-1", "missing-2"), "My Style", PersonaCategory.PERSONAL)

        assertEquals(PersonaExtractionResult.NoSourceConversations(listOf("missing-1", "missing-2")), result)
        assertEquals(emptyList(), personaStore.list())
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `a malformed extraction saves nothing and logs a warning`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Text("not json"))
        val personaStore = InMemoryPersonaStore()
        val logger = RecordingManagerLogger()
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), personaStore, conversationStore("chat-1"), provider, logger)

        val result = manager.createPersonaFromConversations(listOf("chat-1"), "My Style", PersonaCategory.PERSONAL)

        assertIs<PersonaExtractionResult.Malformed>(result)
        assertEquals(emptyList(), personaStore.list())
        assertEquals(1, logger.events.size)
        assertEquals(LogLevel.WARN, logger.events.single().level)
    }

    @Test
    fun `a provider failure saves nothing and logs a warning`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Error(LlmError.Authentication("invalid API key")))
        val personaStore = InMemoryPersonaStore()
        val logger = RecordingManagerLogger()
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), personaStore, conversationStore("chat-1"), provider, logger)

        val result = manager.createPersonaFromConversations(listOf("chat-1"), "My Style", PersonaCategory.PERSONAL)

        assertIs<PersonaExtractionResult.ProviderFailed>(result)
        assertEquals(emptyList(), personaStore.list())
        assertEquals("persona_creation_provider_failed", logger.events.single().message)
    }

    @Test
    fun `setActivePersona returns false and changes nothing for an unknown id`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val personaStore = InMemoryPersonaStore()
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), personaStore, InMemoryConversationStore(), provider)

        assertEquals(false, manager.setActivePersona("nope", true))
    }

    @Test
    fun `setActivePersona true then false is reflected by re-loading from the store`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val personaStore = InMemoryPersonaStore()
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), personaStore, conversationStore("chat-1"), provider)
        val persona = (manager.createPersonaFromConversations(listOf("chat-1"), "My Style", PersonaCategory.PERSONAL) as PersonaExtractionResult.Success).persona
        assertEquals(false, persona.enabled)

        assertEquals(true, manager.setActivePersona(persona.id, true))
        assertEquals(true, personaStore.load(persona.id)!!.enabled)

        assertEquals(true, manager.setActivePersona(persona.id, false))
        assertEquals(false, personaStore.load(persona.id)!!.enabled)
    }

    @Test
    fun `testPersona returns null for an unknown persona id`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), InMemoryPersonaStore(), InMemoryConversationStore(), provider)

        assertNull(manager.testPersona("nope", listOf("hi")))
    }

    @Test
    fun `testPersona drives a real request per sample prompt using the persona's context contribution as the system prompt`() {
        val creationProvider = ScriptedManagerLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val personaStore = InMemoryPersonaStore()
        val creationManager = DefaultPersonaManager(LlmPersonaExtractor(creationProvider), personaStore, conversationStore("chat-1"), creationProvider)
        val persona = (creationManager.createPersonaFromConversations(listOf("chat-1"), "My Style", PersonaCategory.PERSONAL) as PersonaExtractionResult.Success).persona

        val testProvider = ScriptedManagerLlmProvider(LlmResponse.Text("reply one"), LlmResponse.Text("reply two"))
        val testManager = DefaultPersonaManager(LlmPersonaExtractor(testProvider), personaStore, InMemoryConversationStore(), testProvider)

        val result = testManager.testPersona(persona.id, listOf("prompt one", "prompt two"))!!

        assertEquals(persona.id, result.personaId)
        assertEquals(listOf("prompt one", "prompt two"), result.responses.map { it.prompt })
        assertEquals(listOf(LlmResponse.Text("reply one"), LlmResponse.Text("reply two")), result.responses.map { it.response })
        assertTrue(testProvider.requests.all { it.systemPrompt == "Write warmly and concisely." })
    }

    @Test
    fun `listPersonas returns every saved persona`() {
        val provider = ScriptedManagerLlmProvider(LlmResponse.Text(VALID_STYLE_JSON))
        val personaStore = InMemoryPersonaStore()
        val manager = DefaultPersonaManager(LlmPersonaExtractor(provider), personaStore, conversationStore("chat-1", "chat-2"), provider)

        manager.createPersonaFromConversations(listOf("chat-1"), "Style A", PersonaCategory.PERSONAL)
        manager.createPersonaFromConversations(listOf("chat-2"), "Style B", PersonaCategory.CODING)

        assertEquals(setOf("Style A", "Style B"), manager.listPersonas().map { it.name }.toSet())
    }
}
