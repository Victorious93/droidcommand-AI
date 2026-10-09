package ai.droidcommand.agent.memory

import ai.droidcommand.agent.KnowledgeGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * The user-facing controls of Part 1 §4: inspect, edit, pin, export, delete. [onForgotten] is the hook
 * for index/embedding/cache invalidation; nothing in this repo keeps such a derived copy yet (retrieval
 * reads the graph directly), so today deletion removing the entity and its edges *is* full propagation —
 * a future embedding index must register here.
 */
class MemoryAdmin(
    private val graph: KnowledgeGraph,
    private val audit: MemoryAuditSink = MemoryAuditSink { },
    private val clock: () -> Instant = Instant::now,
    private val onForgotten: (String) -> Unit = { },
) {
    fun inspect(id: String): MemoryRecord? = graph.getEntity(id)?.let { e -> MemoryMetadata.from(e)?.let { MemoryRecord(e, it) } }

    fun list(scopes: Set<String>? = null): List<MemoryRecord> =
        MemoryRecords.all(graph).filter { scopes == null || it.meta.scope in scopes }

    /** Pinning a superseded record is refused (it is no longer retrievable). Returns whether the record exists and was updated. */
    fun pin(id: String, pinned: Boolean): Boolean {
        val r = inspect(id) ?: return false
        if (r.meta.verification == Verification.SUPERSEDED) return false
        graph.addEntity(r.entity.copy(updatedAt = clock(), properties = r.meta.copy(pinned = pinned).toProperties()))
        audit.record("memory.pin id=$id pinned=$pinned")
        return true
    }

    /**
     * A user edit: re-runs the sensitivity check (an edit cannot smuggle a credential in) and marks the
     * record [Verification.USER_ASSERTED] / [Origin.USER]. Returns the failure reason, or `null` on success.
     */
    fun edit(id: String, title: String? = null, content: String? = null): String? {
        val r = inspect(id) ?: return "no such memory"
        val newTitle = (title ?: r.entity.label).trim()
        val newContent = (content ?: r.meta.content).trim()
        if (newTitle.isEmpty() || newContent.isEmpty()) return "title and content must be non-blank"
        if (newContent.length > MemoryWriter.MAX_CONTENT_CHARS) return "content too long"
        val c = SensitiveContentClassifier.classify("$newTitle\n$newContent")
        if (c.sensitivity == Sensitivity.SECRET) return "contains credential-like content; not stored"
        graph.addEntity(
            r.entity.copy(
                label = newTitle,
                updatedAt = clock(),
                properties = r.meta.copy(
                    content = newContent,
                    origin = Origin.USER,
                    verification = if (r.meta.verification == Verification.SUPERSEDED) Verification.SUPERSEDED else Verification.USER_ASSERTED,
                    sensitivity = c.sensitivity,
                    summary = null, // a stale summary of the old text must not outlive the edit
                ).toProperties(),
            ),
        )
        audit.record("memory.edit id=$id")
        return null
    }

    /** Deletes the memory and (via the graph's cascade) every edge touching it. */
    fun forget(id: String): Boolean {
        if (inspect(id) == null) return false
        val removed = graph.removeEntity(id)
        if (removed) {
            onForgotten(id)
            audit.record("memory.forget id=$id")
        }
        return removed
    }

    /** Deletes every memory in [scope]; returns the count. Non-memory entities are never touched. */
    fun forgetScope(scope: String): Int = list(setOf(scope)).count { forget(it.id) }

    /** JSON export of the memories in [scopes] (all when `null`), including superseded originals for provenance. */
    fun export(scopes: Set<String>? = null): String {
        val items = list(scopes).map { r ->
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(r.id),
                    "title" to JsonPrimitive(r.entity.label),
                    "class" to JsonPrimitive(r.meta.memoryClass.name),
                    "scope" to JsonPrimitive(r.meta.scope),
                    "content" to JsonPrimitive(r.meta.content),
                    "summary" to (r.meta.summary?.let(::JsonPrimitive) ?: JsonNull),
                    "origin" to JsonPrimitive(r.meta.origin.name),
                    "verification" to JsonPrimitive(r.meta.verification.name),
                    "sensitivity" to JsonPrimitive(r.meta.sensitivity.name),
                    "confidence" to JsonPrimitive(r.meta.confidence),
                    "pinned" to JsonPrimitive(r.meta.pinned),
                    "subject" to (r.meta.subject?.let(::JsonPrimitive) ?: JsonNull),
                    "sourceRef" to (r.meta.sourceRef?.let(::JsonPrimitive) ?: JsonNull),
                    "tags" to JsonArray(r.meta.tags.sorted().map(::JsonPrimitive)),
                    "createdAt" to JsonPrimitive(r.entity.createdAt.toString()),
                    "updatedAt" to JsonPrimitive(r.entity.updatedAt.toString()),
                ),
            )
        }
        val root = JsonObject(mapOf("schemaVersion" to JsonPrimitive(MemoryMetadata.SCHEMA_VERSION), "memories" to JsonArray(items)))
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), root)
    }
}
