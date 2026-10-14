package ai.droidcommand.llm

import ai.droidcommand.agent.ConversationContext
import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.memory.MemoryCandidate
import ai.droidcommand.agent.memory.MemoryClass
import ai.droidcommand.agent.memory.MemoryDecision
import ai.droidcommand.agent.memory.MemoryWriter
import ai.droidcommand.agent.memory.Origin

/** Per-decision tally of one [MemoryExtractionService.extractAndStore] run. */
data class MemoryExtractionOutcome(
    val stored: Int,
    val duplicates: Int,
    val conflicts: Int,
    val rejected: Int,
    /** Relationships remapped to the writer's final entity ids and written via [MemoryWriter.writeRelationship]. */
    val relationshipsStored: Int,
    /** Relationships whose `from`/`to` entity was [MemoryDecision.Conflict]ed or [MemoryDecision.Rejected], so no final id existed to remap to. */
    val relationshipsDropped: Int,
)

/**
 * The write half of Second Brain extraction: runs [LlmGraphExtractor] and sends every extracted entity through
 * [MemoryWriter] (sensitivity screening, duplicate and conflict handling, provenance) into [scope], instead of
 * writing it straight into the graph the way [GraphExtractionService] does.
 *
 * Everything extracted is a model claim: origin is always [Origin.MODEL], so [MemoryWriter] can never record it
 * as `VERIFIED`, and confidence is a fixed low [CONFIDENCE]. Nothing here sets `explicitUserSave`, so extracted
 * personal data is rejected rather than stored.
 *
 * Extracted relationships reference the extractor's own per-response entity ids (see [LlmGraphExtractor]), not
 * the ids [MemoryWriter] assigns on write — so each entity's extractor id is remapped to whatever id it actually
 * ended up stored under ([MemoryDecision.Stored]'s new id, or [MemoryDecision.Duplicate]'s existing id) before a
 * relationship referencing it is written; a relationship whose end was [MemoryDecision.Conflict]ed or
 * [MemoryDecision.Rejected] has no final id to remap to and is counted in
 * [MemoryExtractionOutcome.relationshipsDropped] instead of written.
 *
 * Extraction quality against a real provider is unmeasured, as for every other `core-llm` extraction prompt.
 */
class MemoryExtractionService(
    private val extractor: LlmGraphExtractor,
    private val writer: MemoryWriter,
    private val scope: String,
) {
    init {
        require(scope.isNotBlank()) { "scope must be non-blank" }
    }

    fun extractAndStore(context: ConversationContext, source: String): Pair<GraphExtractionResult, MemoryExtractionOutcome?> {
        val result = extractor.extract(context, source)
        if (result !is GraphExtractionResult.Success) return result to null
        var stored = 0
        var duplicates = 0
        var conflicts = 0
        var rejected = 0
        val finalIdByExtractedId = mutableMapOf<String, String>()
        for (e in result.entities) {
            when (val decision = writer.write(candidate(e, source))) {
                is MemoryDecision.Stored -> {
                    stored++
                    finalIdByExtractedId[e.id] = decision.entity.id
                }
                is MemoryDecision.Duplicate -> {
                    duplicates++
                    finalIdByExtractedId[e.id] = decision.existingId
                }
                is MemoryDecision.Conflict -> conflicts++
                is MemoryDecision.Rejected -> rejected++
            }
        }
        var relationshipsStored = 0
        var relationshipsDropped = 0
        for (r in result.relationships) {
            val fromId = finalIdByExtractedId[r.fromId]
            val toId = finalIdByExtractedId[r.toId]
            if (fromId != null && toId != null && writer.writeRelationship(fromId, toId, r.type, source)) {
                relationshipsStored++
            } else {
                relationshipsDropped++
            }
        }
        return result to MemoryExtractionOutcome(stored, duplicates, conflicts, rejected, relationshipsStored, relationshipsDropped)
    }

    private fun candidate(e: Entity, source: String): MemoryCandidate {
        val details = e.properties.entries.joinToString("; ") { (k, v) -> "$k: $v" }
        return MemoryCandidate(
            title = e.label,
            content = if (details.isEmpty()) e.label else "${e.label} — $details",
            memoryClass = classFor(e.type),
            scope = scope,
            origin = Origin.MODEL,
            confidence = CONFIDENCE,
            sourceRef = source,
        )
    }

    companion object {
        const val CONFIDENCE = 0.4

        internal fun classFor(type: EntityType): MemoryClass = when (type) {
            EntityType.DEVICE, EntityType.EXECUTION_TARGET -> MemoryClass.DEVICE
            EntityType.PROJECT -> MemoryClass.PROJECT
            EntityType.CONVERSATION -> MemoryClass.CONVERSATION
            EntityType.PERSONA, EntityType.NOTE -> MemoryClass.USER
            EntityType.LOG, EntityType.TASK, EntityType.AUTOMATION, EntityType.COMMAND -> MemoryClass.AGENT
            else -> MemoryClass.KNOWLEDGE
        }
    }
}
