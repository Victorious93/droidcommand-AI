package ai.droidcommand.agent.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import java.time.Instant

/** The six memory classes of the Second Brain spec (Part 1 §2). */
enum class MemoryClass(val entityType: EntityType) {
    USER(EntityType.NOTE),
    PROJECT(EntityType.PROJECT),
    CONVERSATION(EntityType.CONVERSATION),
    AGENT(EntityType.LOG),
    DEVICE(EntityType.DEVICE),
    KNOWLEDGE(EntityType.DOCUMENT),
}

/** Who produced the content. Separate from [Verification]: a model can *claim* something, but that never makes it verified. */
enum class Origin { USER, MODEL, TOOL, IMPORT }

/**
 * How far the content is trusted. [trust] orders them for dedupe/conflict decisions; [SUPERSEDED] is
 * terminal and set only by [MemoryWriter] when a newer fact replaces this one.
 */
enum class Verification(val trust: Int) {
    VERIFIED(4),
    USER_ASSERTED(3),
    MODEL_INFERRED(2),
    HYPOTHESIS(1),
    SUPERSEDED(0),
}

/** [SECRET] content is never persisted. [PERSONAL] is persisted only on an explicit user save. */
enum class Sensitivity { NORMAL, PERSONAL, SECRET }

/** Relationship type names from the spec's example list, as the free-text [ai.droidcommand.agent.Relationship.type]. */
object MemoryRelations {
    const val PROJECT_HAS_MODULE = "PROJECT_HAS_MODULE"
    const val MODULE_DEPENDS_ON = "MODULE_DEPENDS_ON"
    const val TASK_BLOCKED_BY = "TASK_BLOCKED_BY"
    const val DECISION_AFFECTS = "DECISION_AFFECTS"
    const val DEVICE_SUPPORTS = "DEVICE_SUPPORTS"
    const val ERROR_RESOLVED_BY = "ERROR_RESOLVED_BY"
    const val MEMORY_RELATES_TO = "MEMORY_RELATES_TO"
    const val CONVERSATION_REFERENCES = "CONVERSATION_REFERENCES"
    const val USER_PREFERS = "USER_PREFERS"

    /** Directed: `from` supersedes `to`. Not symmetric, even though traversal is direction-agnostic. */
    const val FACT_SUPERSEDES = "FACT_SUPERSEDES"
    const val SOURCE_SUPPORTS = "SOURCE_SUPPORTS"
}

/**
 * Second Brain fields carried inside [Entity.properties] under a `mem.` prefix, so the existing
 * [ai.droidcommand.agent.KnowledgeGraph] implementations (in-memory, JSON file, Room) store them
 * unchanged — no schema migration and no second database. Plain (non-memory) entities have no
 * `mem.class` key and are ignored by everything in this package.
 *
 * Scope strings are opaque: `global`, `project:<id>`, `device:<id>`, `conversation:<id>`. Isolation is
 * enforced by [MemoryRetriever] requiring an explicit allowed-scope set.
 */
data class MemoryMetadata(
    val memoryClass: MemoryClass,
    val content: String,
    val scope: String,
    val origin: Origin,
    val verification: Verification,
    val sensitivity: Sensitivity = Sensitivity.NORMAL,
    val confidence: Double = 0.5,
    val pinned: Boolean = false,
    val subject: String? = null,
    val summary: String? = null,
    val sourceRef: String? = null,
    val tags: Set<String> = emptySet(),
    /** Days after the entity's `createdAt` at which it stops being retrievable. `null` = keep. */
    val retentionDays: Int? = null,
) {
    fun toProperties(): Map<String, String> = buildMap {
        put(K_VERSION, SCHEMA_VERSION.toString())
        put(K_CLASS, memoryClass.name)
        put(K_CONTENT, content)
        put(K_SCOPE, scope)
        put(K_ORIGIN, origin.name)
        put(K_VERIFICATION, verification.name)
        put(K_SENSITIVITY, sensitivity.name)
        put(K_CONFIDENCE, confidence.coerceIn(0.0, 1.0).toString())
        put(K_PINNED, pinned.toString())
        subject?.let { put(K_SUBJECT, it) }
        summary?.let { put(K_SUMMARY, it) }
        sourceRef?.let { put(K_SOURCE, it) }
        if (tags.isNotEmpty()) put(K_TAGS, tags.sorted().joinToString(TAG_SEP.toString()))
        retentionDays?.let { put(K_RETENTION, it.toString()) }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val K_VERSION = "mem.v"
        const val K_CLASS = "mem.class"
        const val K_CONTENT = "mem.content"
        const val K_SCOPE = "mem.scope"
        const val K_ORIGIN = "mem.origin"
        const val K_VERIFICATION = "mem.verification"
        const val K_SENSITIVITY = "mem.sensitivity"
        const val K_CONFIDENCE = "mem.confidence"
        const val K_PINNED = "mem.pinned"
        const val K_SUBJECT = "mem.subject"
        const val K_SUMMARY = "mem.summary"
        const val K_SOURCE = "mem.source"
        const val K_TAGS = "mem.tags"
        const val K_RETENTION = "mem.retentionDays"
        private const val TAG_SEP = '\u001F'

        /** `null` for a non-memory entity or one whose memory fields are malformed (never throws on bad stored data). */
        fun from(entity: Entity): MemoryMetadata? {
            val p = entity.properties
            val memoryClass = p[K_CLASS]?.let { runCatching { MemoryClass.valueOf(it) }.getOrNull() } ?: return null
            val content = p[K_CONTENT] ?: return null
            val scope = p[K_SCOPE] ?: return null
            val origin = p[K_ORIGIN]?.let { runCatching { Origin.valueOf(it) }.getOrNull() } ?: return null
            val verification = p[K_VERIFICATION]?.let { runCatching { Verification.valueOf(it) }.getOrNull() } ?: return null
            return MemoryMetadata(
                memoryClass = memoryClass,
                content = content,
                scope = scope,
                origin = origin,
                verification = verification,
                sensitivity = p[K_SENSITIVITY]?.let { runCatching { Sensitivity.valueOf(it) }.getOrNull() } ?: Sensitivity.NORMAL,
                confidence = p[K_CONFIDENCE]?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.5,
                pinned = p[K_PINNED] == "true",
                subject = p[K_SUBJECT],
                summary = p[K_SUMMARY],
                sourceRef = p[K_SOURCE],
                tags = p[K_TAGS]?.split(TAG_SEP)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet(),
                retentionDays = p[K_RETENTION]?.toIntOrNull(),
            )
        }
    }
}

/** An [Entity] together with its parsed [MemoryMetadata]. */
data class MemoryRecord(val entity: Entity, val meta: MemoryMetadata) {
    val id: String get() = entity.id

    fun isExpired(now: Instant): Boolean {
        val days = meta.retentionDays ?: return false
        return now.isAfter(entity.createdAt.plusSeconds(days.toLong() * 86_400))
    }
}
