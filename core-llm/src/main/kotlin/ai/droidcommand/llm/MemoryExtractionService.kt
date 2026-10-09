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
    /** Extracted relationships are not stored on this path; see [MemoryExtractionService]. */
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
 * **Not done:** extracted relationships are counted in [MemoryExtractionOutcome.relationshipsDropped] and not
 * written — the writer assigns its own ids and deduplicates, so an edge between extracted ids would dangle or
 * point at the wrong record; remapping them is future work. Extraction quality against a real provider is
 * unmeasured, as for every other `core-llm` extraction prompt.
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
        for (e in result.entities) {
            when (writer.write(candidate(e, source))) {
                is MemoryDecision.Stored -> stored++
                is MemoryDecision.Duplicate -> duplicates++
                is MemoryDecision.Conflict -> conflicts++
                is MemoryDecision.Rejected -> rejected++
            }
        }
        return result to MemoryExtractionOutcome(stored, duplicates, conflicts, rejected, result.relationships.size)
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
