package ai.droidcommand.llm

import ai.droidcommand.agent.ImportedConversation
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger
import ai.droidcommand.agent.PersonaCategory
import ai.droidcommand.agent.PersonaStore

/**
 * Ties [LlmPersonaExtractor] to a [PersonaStore]: calls the extractor,
 * then saves whatever it returns — the same "caller policy" wiring
 * [KnowledgeExtractionService] already provides for [LlmKnowledgeExtractor].
 *
 * Deliberately not invoked automatically from anywhere — `core-agent`
 * still has no dependency on `core-llm`, and this class doesn't change
 * that boundary.
 */
class PersonaExtractionService(
    private val extractor: LlmPersonaExtractor,
    private val store: PersonaStore,
    private val logger: Logger = NoOpLogger,
) {
    fun extractAndSave(
        conversation: ImportedConversation,
        name: String,
        category: PersonaCategory,
        sourceConversationId: String,
    ): PersonaExtractionResult {
        val result = extractor.extract(conversation, name, category, sourceConversationId)
        when (result) {
            is PersonaExtractionResult.Success -> {
                store.save(result.persona)
                logger.info("persona_extracted", mapOf("name" to name, "personaId" to result.persona.id))
            }
            is PersonaExtractionResult.Malformed ->
                logger.warn("persona_extraction_malformed", mapOf("name" to name, "reason" to result.reason))
            is PersonaExtractionResult.ProviderFailed ->
                logger.warn("persona_extraction_provider_failed", mapOf("name" to name, "error" to result.error.message))
        }
        return result
    }
}
