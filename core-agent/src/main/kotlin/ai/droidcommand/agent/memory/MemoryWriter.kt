package ai.droidcommand.agent.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Relationship
import java.time.Instant
import java.util.UUID

/** Receives a one-line event per memory mutation. Events never contain memory content or secrets. */
fun interface MemoryAuditSink {
    fun record(event: String)
}

/** A proposed memory, before the write pipeline has judged it. */
data class MemoryCandidate(
    val title: String,
    val content: String,
    val memoryClass: MemoryClass,
    val scope: String,
    val origin: Origin,
    /** What the caller *asks* for; the pipeline may lower it (see [MemoryWriter.effectiveVerification]). `null` = the default for [origin]. */
    val requestedVerification: Verification? = null,
    val confidence: Double = 0.5,
    /** Identifies "the same fact slot" (e.g. `preferred-language`); enables conflict detection. No subject = no conflict check. */
    val subject: String? = null,
    val summary: String? = null,
    val tags: Set<String> = emptySet(),
    val sourceRef: String? = null,
    /** True only when the user explicitly asked to save this. Required to persist [Sensitivity.PERSONAL] content. */
    val explicitUserSave: Boolean = false,
    val pinned: Boolean = false,
    val retentionDays: Int? = null,
)

sealed interface MemoryDecision {
    /** Persisted. [supersededId] is the older record this one replaced, if any. */
    data class Stored(val entity: Entity, val supersededId: String?, val notes: List<String>) : MemoryDecision

    /** Already known. [reinforced] = the existing record's verification was raised because the candidate is more trusted. */
    data class Duplicate(val existingId: String, val reinforced: Boolean) : MemoryDecision

    /** Disagrees with a more trusted existing record on the same subject; nothing was written. The caller must ask the user. */
    data class Conflict(val existingId: String, val reason: String) : MemoryDecision

    data class Rejected(val reason: String) : MemoryDecision
}

/**
 * The write half of the Second Brain pipeline (Part 1 §4): validate → sensitivity → provenance →
 * duplicate → conflict → store → relationship update.
 *
 * Not implemented here, by design of this slice: automatic *extraction* of candidates from a
 * conversation (the existing `core-llm` `GraphExtractionService` does that and can feed this), and
 * embedding/FTS index updates (no embedding index exists in this repo yet; retrieval is keyword + graph).
 *
 * Duplicate detection is O(n) over stored memories ([KnowledgeGraph.searchEntities] with an empty
 * keyword lists everything) — fine for thousands, unmeasured beyond that.
 */
class MemoryWriter(
    private val graph: KnowledgeGraph,
    private val audit: MemoryAuditSink = MemoryAuditSink { },
    private val clock: () -> Instant = Instant::now,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    /** Token-set Jaccard at or above which two memories count as the same. */
    private val duplicateThreshold: Double = 0.9,
) {
    fun write(candidate: MemoryCandidate): MemoryDecision {
        val title = candidate.title.trim()
        val content = candidate.content.trim()
        if (title.isEmpty() || content.isEmpty()) return reject("title and content must be non-blank")
        if (content.length > MAX_CONTENT_CHARS) return reject("content exceeds $MAX_CONTENT_CHARS characters; store a summary")
        if (candidate.scope.isBlank()) return reject("scope must be non-blank")
        if (candidate.retentionDays != null && candidate.retentionDays <= 0) return reject("retentionDays must be positive")

        val classification = SensitiveContentClassifier.classify("$title\n$content\n${candidate.summary.orEmpty()}")
        when (classification.sensitivity) {
            Sensitivity.SECRET -> return reject("contains credential-like content (${classification.reasons.joinToString()}); not stored")
            Sensitivity.PERSONAL -> if (!candidate.explicitUserSave) {
                return reject("contains personal data (${classification.reasons.joinToString()}); stored only on an explicit user save")
            }
            Sensitivity.NORMAL -> Unit
        }

        val notes = mutableListOf<String>()
        val verification = effectiveVerification(candidate.origin, candidate.requestedVerification)
        if (candidate.requestedVerification != null && candidate.requestedVerification != verification) {
            notes += "requested ${candidate.requestedVerification} lowered to $verification (origin ${candidate.origin})"
        }

        val active = activeRecords().filter { it.meta.memoryClass == candidate.memoryClass && it.meta.scope == candidate.scope }

        val normalized = tokens(content)
        active.firstOrNull { jaccard(tokens(it.meta.content), normalized) >= duplicateThreshold }?.let { existing ->
            val reinforced = verification.trust > existing.meta.verification.trust
            if (reinforced) {
                put(existing.entity.copy(updatedAt = clock(), properties = existing.meta.copy(verification = verification).toProperties()))
                audit.record("memory.reinforce id=${existing.id} to=$verification")
            }
            return MemoryDecision.Duplicate(existing.id, reinforced)
        }

        var supersededId: String? = null
        val subject = candidate.subject?.trim()?.takeIf { it.isNotEmpty() }
        if (subject != null) {
            val clash = active.firstOrNull { it.meta.subject == subject }
            if (clash != null) {
                val userCorrection = candidate.origin == Origin.USER && candidate.explicitUserSave
                if (verification.trust < clash.meta.verification.trust && !userCorrection) {
                    audit.record("memory.conflict existing=${clash.id}")
                    return MemoryDecision.Conflict(
                        clash.id,
                        "existing ${clash.meta.verification} memory on subject '$subject' outranks this ${verification} candidate",
                    )
                }
                supersededId = clash.id
            }
        }

        val now = clock()
        val meta = MemoryMetadata(
            memoryClass = candidate.memoryClass,
            content = content,
            scope = candidate.scope,
            origin = candidate.origin,
            verification = verification,
            sensitivity = classification.sensitivity,
            confidence = candidate.confidence.coerceIn(0.0, 1.0),
            pinned = candidate.pinned,
            subject = subject,
            summary = candidate.summary?.trim()?.takeIf { it.isNotEmpty() },
            sourceRef = candidate.sourceRef,
            tags = candidate.tags,
            retentionDays = candidate.retentionDays,
        )
        val entity = Entity(
            id = idGenerator(),
            type = candidate.memoryClass.entityType,
            label = title,
            properties = meta.toProperties(),
            createdAt = now,
            updatedAt = now,
        )
        put(entity)

        if (supersededId != null) {
            val old = graph.getEntity(supersededId)
            val oldMeta = old?.let { MemoryMetadata.from(it) }
            if (old != null && oldMeta != null) {
                // The original is kept (summaries/replacements never overwrite source material); it just stops being retrievable.
                put(old.copy(updatedAt = now, properties = oldMeta.copy(verification = Verification.SUPERSEDED, pinned = false).toProperties()))
                graph.addRelationship(
                    Relationship(
                        id = idGenerator(),
                        fromId = entity.id,
                        toId = old.id,
                        type = MemoryRelations.FACT_SUPERSEDES,
                        properties = candidate.sourceRef?.let { mapOf("evidence" to it) } ?: emptyMap(),
                        createdAt = now,
                    ),
                )
                notes += "supersedes ${old.id}"
            }
        }
        audit.record("memory.store id=${entity.id} class=${candidate.memoryClass} scope=${candidate.scope} verification=$verification")
        return MemoryDecision.Stored(entity, supersededId, notes)
    }

    private fun put(entity: Entity) = graph.addEntity(entity)

    private fun reject(reason: String): MemoryDecision.Rejected {
        audit.record("memory.reject reason=${reason.substringBefore(" (")}")
        return MemoryDecision.Rejected(reason)
    }

    private fun activeRecords(): List<MemoryRecord> =
        MemoryRecords.all(graph).filter { it.meta.verification != Verification.SUPERSEDED }

    companion object {
        const val MAX_CONTENT_CHARS = 8_000

        /**
         * The rule that keeps a model inference from silently becoming a fact: content whose origin is
         * [Origin.MODEL] can be at most [Verification.MODEL_INFERRED], whatever was requested. Only a
         * tool observation ([Origin.TOOL]) can be [Verification.VERIFIED]; a user's own statement is
         * [Verification.USER_ASSERTED] at most. [Verification.SUPERSEDED] can never be requested.
         */
        fun effectiveVerification(origin: Origin, requested: Verification?): Verification {
            // The ceiling is also the default: an unspecified request gets the most the origin can justify.
            val ceiling = when (origin) {
                Origin.TOOL -> Verification.VERIFIED
                Origin.USER -> Verification.USER_ASSERTED
                Origin.MODEL -> Verification.MODEL_INFERRED
                Origin.IMPORT -> Verification.HYPOTHESIS
            }
            val want = requested?.takeIf { it != Verification.SUPERSEDED } ?: ceiling
            return if (want.trust > ceiling.trust) ceiling else want
        }

        internal fun tokens(text: String): Set<String> =
            text.lowercase().split(ai.droidcommand.agent.GraphRetriever.NON_WORD).filter { it.isNotEmpty() }.toSet()

        internal fun jaccard(a: Set<String>, b: Set<String>): Double {
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val inter = a.intersect(b).size
            return inter.toDouble() / (a.size + b.size - inter)
        }
    }
}

/** Read helpers over a graph that may mix memory and non-memory entities. */
object MemoryRecords {
    /** Every memory record, deterministic order (by entity id). Non-memory and malformed entities are skipped. */
    fun all(graph: KnowledgeGraph): List<MemoryRecord> =
        graph.searchEntities("").mapNotNull { e -> MemoryMetadata.from(e)?.let { MemoryRecord(e, it) } }
}
