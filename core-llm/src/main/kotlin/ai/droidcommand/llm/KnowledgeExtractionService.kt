package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.KnowledgeStore
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger

/**
 * Ties [LlmKnowledgeExtractor] to a [KnowledgeStore]: calls the extractor,
 * then saves whatever it returns. This is the "caller policy" wiring both
 * [LlmKnowledgeExtractor] and [ai.droidcommand.agent.formatKnowledgeContext]
 * deliberately left undone — deciding *when* to call [extractAndSave] (every
 * turn? end of conversation? on a timer?) is still entirely up to the
 * caller; this class only removes the boilerplate of dispatching on
 * [KnowledgeExtractionResult] once that decision has been made.
 *
 * Deliberately not invoked automatically from
 * [ai.droidcommand.agent.ObjectiveEngine]/[ai.droidcommand.agent.DroidCommandSession] —
 * `core-agent` still has no dependency on `core-llm`, and this class doesn't
 * change that boundary.
 */
class KnowledgeExtractionService(
    private val extractor: LlmKnowledgeExtractor,
    private val store: KnowledgeStore,
    private val logger: Logger = NoOpLogger,
) {
    fun extractAndSave(context: ConversationContext, source: String): KnowledgeExtractionResult {
        val result = extractor.extract(context, source)
        when (result) {
            is KnowledgeExtractionResult.Success -> {
                result.entries.forEach(store::save)
                logger.info("knowledge_extracted", mapOf("source" to source, "count" to result.entries.size.toString()))
            }
            is KnowledgeExtractionResult.Malformed ->
                logger.warn("knowledge_extraction_malformed", mapOf("source" to source, "reason" to result.reason))
            is KnowledgeExtractionResult.ProviderFailed ->
                logger.warn("knowledge_extraction_provider_failed", mapOf("source" to source, "error" to result.error.message))
        }
        return result
    }
}
