package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationStore
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.Message
import ai.droidcommand.agent.NoOpLogger
import ai.droidcommand.agent.Persona
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.PersonaStore
import ai.droidcommand.agent.Role

/** One [PersonaManager.testPersona] round trip: a sample prompt and the raw response the persona's context contribution produced for it. */
data class PersonaTestResponse(val prompt: String, val response: LlmResponse)

/** The outcome of [PersonaManager.testPersona] — one [PersonaTestResponse] per requested sample prompt, same order. */
data class PersonaTestResult(val personaId: String, val responses: List<PersonaTestResponse>)

/**
 * CAP-005, P0.5's `PersonaManager`. **Deliberate deviation from the roadmap
 * prompt's literal `suspend fun`/`List<File>` signatures**, the same
 * reasoning [DefaultAiProviderSelector] already documents: no module in this
 * repository has any coroutines exposure, so every method here is a plain
 * `fun`; and `createPersonaFromConversation(files: List<File>, ...)` becomes
 * [createPersonaFromConversations] taking already-imported conversation ids
 * (resolved via [ConversationStore]) rather than raw file handles — no
 * interface in `core-agent`/`core-llm` takes a `java.io.File` anywhere, and
 * a caller uploads + imports via CAP-006's `ConversationImporter` first,
 * then builds a persona from what's already stored, rather than this class
 * duplicating file-reading.
 */
interface PersonaManager {
    fun createPersonaFromConversations(sourceConversationIds: List<String>, name: String, category: PersonaCategory): PersonaExtractionResult

    fun setActivePersona(personaId: String, enabled: Boolean): Boolean

    fun testPersona(personaId: String, samplePrompts: List<String>): PersonaTestResult?

    fun listPersonas(): List<Persona>
}

/**
 * Ties [LlmPersonaExtractor] to [PersonaStore] and [ConversationStore], the
 * same three-way composition role [KnowledgeExtractionService] already
 * plays for [LlmKnowledgeExtractor]/[ai.droidcommand.agent.KnowledgeStore].
 *
 * Deliberately not invoked automatically from
 * [ai.droidcommand.agent.ObjectiveEngine]/[ai.droidcommand.agent.DroidCommandSession]
 * — `core-agent` still has no dependency on `core-llm`, and this class
 * doesn't change that boundary.
 */
class DefaultPersonaManager(
    private val extractor: LlmPersonaExtractor,
    private val personaStore: PersonaStore,
    private val conversationStore: ConversationStore,
    private val provider: LlmProvider,
    private val logger: Logger = NoOpLogger,
) : PersonaManager {
    /**
     * Resolves every id in [sourceConversationIds] via [conversationStore],
     * keeping only the ones that actually resolve. If none resolve, returns
     * [PersonaExtractionResult.NoSourceConversations] without ever calling
     * [extractor] — otherwise the resulting [Persona.sourceConversations]
     * reflects only the ids genuinely analyzed, honestly narrower than
     * [sourceConversationIds] when some didn't resolve.
     */
    override fun createPersonaFromConversations(
        sourceConversationIds: List<String>,
        name: String,
        category: PersonaCategory,
    ): PersonaExtractionResult {
        val resolved = sourceConversationIds.mapNotNull { id -> conversationStore.load(id)?.let { id to it } }
        if (resolved.isEmpty()) {
            logger.warn("persona_creation_no_source_conversations", mapOf("requestedIds" to sourceConversationIds.joinToString(",")))
            return PersonaExtractionResult.NoSourceConversations(sourceConversationIds)
        }

        val result = extractor.extract(
            sourceConversations = resolved.map { it.second },
            sourceConversationIds = resolved.map { it.first },
            name = name,
            category = category,
        )
        when (result) {
            is PersonaExtractionResult.Success -> {
                personaStore.save(result.persona)
                logger.info("persona_created", mapOf("id" to result.persona.id, "name" to name))
            }
            is PersonaExtractionResult.Malformed ->
                logger.warn("persona_creation_malformed", mapOf("reason" to result.reason))
            is PersonaExtractionResult.ProviderFailed ->
                logger.warn("persona_creation_provider_failed", mapOf("error" to result.error.message))
            is PersonaExtractionResult.NoSourceConversations -> Unit // unreachable: the extractor never produces this case
        }
        return result
    }

    override fun setActivePersona(personaId: String, enabled: Boolean): Boolean {
        val persona = personaStore.load(personaId) ?: return false
        personaStore.save(persona.copy(enabled = enabled))
        return true
    }

    /**
     * Proves only that [personaId]'s [Persona.contextContribution] can
     * drive a real request/response round trip for each of [samplePrompts]
     * — it does not judge whether the responses actually match the
     * persona's style. Automated style-match scoring would need a second,
     * separate LLM-as-judge call this slice does not attempt; left to the
     * caller/human, the same "stays structural, not semantic-judging"
     * restraint `ImportedConversationSummary`'s own doc comment already
     * applies to CAP-006's "Analyzer" step.
     */
    override fun testPersona(personaId: String, samplePrompts: List<String>): PersonaTestResult? {
        val persona = personaStore.load(personaId) ?: return null
        val responses = samplePrompts.map { prompt ->
            val request = LlmRequest(systemPrompt = persona.contextContribution, messages = listOf(Message(Role.USER, prompt)))
            PersonaTestResponse(prompt, provider.complete(request))
        }
        return PersonaTestResult(personaId, responses)
    }

    override fun listPersonas(): List<Persona> = personaStore.list().mapNotNull(personaStore::load)
}
