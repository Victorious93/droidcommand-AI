package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Logger
import ai.droidcommand.agent.NoOpLogger

/**
 * Ties [LlmGraphExtractor] to a [KnowledgeGraph]: calls the extractor, then
 * saves whatever it returns — every entity first, then every relationship,
 * since [KnowledgeGraph.addRelationship] requires both endpoints to already
 * exist (this ordering is load-bearing, not incidental). Mirrors
 * [KnowledgeExtractionService] exactly, including its restraint: deciding
 * *when* to call [extractAndSave] is still entirely up to the caller, and
 * this is deliberately not invoked automatically from
 * [ai.droidcommand.agent.ObjectiveEngine]/[ai.droidcommand.agent.DroidCommandSession] —
 * `core-agent` still has no dependency on `core-llm`, and this class doesn't
 * change that boundary.
 *
 * [source] is logged but not stored on any [ai.droidcommand.agent.Entity]/
 * [ai.droidcommand.agent.Relationship] — neither type carries a `source`
 * field the way [ai.droidcommand.agent.KnowledgeEntry] does; a named gap,
 * not a silently invented field the spec never asked for.
 */
class GraphExtractionService(
    private val extractor: LlmGraphExtractor,
    private val graph: KnowledgeGraph,
    private val logger: Logger = NoOpLogger,
) {
    fun extractAndSave(context: ConversationContext, source: String): GraphExtractionResult {
        val result = extractor.extract(context, source)
        when (result) {
            is GraphExtractionResult.Success -> {
                result.entities.forEach(graph::addEntity)
                result.relationships.forEach(graph::addRelationship)
                logger.info(
                    "graph_extracted",
                    mapOf(
                        "source" to source,
                        "entityCount" to result.entities.size.toString(),
                        "relationshipCount" to result.relationships.size.toString(),
                    ),
                )
            }
            is GraphExtractionResult.Malformed ->
                logger.warn("graph_extraction_malformed", mapOf("source" to source, "reason" to result.reason))
            is GraphExtractionResult.ProviderFailed ->
                logger.warn("graph_extraction_provider_failed", mapOf("source" to source, "error" to result.error.message))
        }
        return result
    }
}
