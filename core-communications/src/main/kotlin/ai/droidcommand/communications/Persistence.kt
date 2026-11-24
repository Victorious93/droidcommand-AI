package ai.droidcommand.communications

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Victorious Framework: durable state for the communications broker. Writes are atomic (temp file then move), so a
 * crash leaves the old state or the new state, never half of each. A file that cannot be read is moved aside, not
 * deleted: grants and approvals then read as EMPTY (no authority), and receipts report unhealthy so sends stop.
 */
internal object JsonFiles {
    fun writeAtomic(path: Path, text: String) {
        path.parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, text.toByteArray(Charsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Parsed array, or null if the file is absent. Throws if it is present but unreadable. */
    fun readArray(path: Path): JsonArray? {
        if (!Files.exists(path)) return null
        return CommsContract.JSON.parseToJsonElement(String(Files.readAllBytes(path), Charsets.UTF_8)) as JsonArray
    }

    fun quarantine(path: Path) {
        runCatching { Files.move(path, path.resolveSibling(path.fileName.toString() + ".corrupt-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING) }
    }

    fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    fun long(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull
    fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull
}

class JsonFileGrantStore(private val file: Path) : GrantStore {
    private val lock = Any()
    private var cache: MutableList<CommsGrant> = load()

    private fun load(): MutableList<CommsGrant> = try {
        JsonFiles.readArray(file)?.map { fromJson(it as JsonObject) }?.toMutableList() ?: mutableListOf()
    } catch (_: Exception) {
        JsonFiles.quarantine(file)
        mutableListOf() // unreadable grants mean NO authority, never "everything"
    }

    private fun save() = JsonFiles.writeAtomic(file, CommsContract.JSON.encodeToString(JsonArray.serializer(), JsonArray(cache.map(::toJson))))

    override fun add(grant: CommsGrant) = synchronized(lock) {
        cache.removeAll { it.id == grant.id }
        cache.add(grant)
        save()
    }

    override fun list(): List<CommsGrant> = synchronized(lock) { cache.toList() }

    override fun revoke(id: String): Boolean = synchronized(lock) {
        val i = cache.indexOfFirst { it.id == id }
        if (i < 0) return false
        cache[i] = cache[i].copy(revoked = true)
        save()
        true
    }

    override fun revokeAll(caller: String): Int = synchronized(lock) {
        var n = 0
        cache.indices.forEach { i ->
            if (cache[i].caller == caller && !cache[i].revoked) {
                cache[i] = cache[i].copy(revoked = true)
                n++
            }
        }
        if (n > 0) save()
        n
    }

    override fun markUsed(id: String) = synchronized(lock) {
        val i = cache.indexOfFirst { it.id == id }
        if (i >= 0) {
            cache[i] = cache[i].copy(uses = cache[i].uses + 1)
            save()
        }
    }

    private fun toJson(g: CommsGrant) = buildJsonObject {
        put("id", g.id)
        put("caller", g.caller)
        g.botId?.let { put("bot", it) }
        g.contacts?.let { put("contacts", JsonArray(it.map(::JsonPrimitive))) }
        put("permissions", JsonArray(g.permissions.map { JsonPrimitive(it.name) }))
        g.expiresAtMs?.let { put("expires", it) }
        put("one_time", g.oneTime)
        put("created", g.createdAtMs)
        put("revoked", g.revoked)
        put("uses", g.uses)
    }

    private fun fromJson(o: JsonObject) = CommsGrant(
        id = JsonFiles.str(o, "id")!!, caller = JsonFiles.str(o, "caller")!!, botId = JsonFiles.str(o, "bot"),
        contacts = (o["contacts"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet(),
        permissions = (o["permissions"] as JsonArray).mapNotNull { runCatching { CommsPermission.valueOf((it as JsonPrimitive).content) }.getOrNull() }.toSet(),
        expiresAtMs = JsonFiles.long(o, "expires"), oneTime = JsonFiles.bool(o, "one_time") ?: false, createdAtMs = JsonFiles.long(o, "created") ?: 0L,
        revoked = JsonFiles.bool(o, "revoked") ?: false, uses = (JsonFiles.long(o, "uses") ?: 0L).toInt(),
    )
}

/** An unreadable or unknown state is loaded as DENIED: it can never turn into an approval. */
class JsonFileApprovalStore(private val file: Path) : ApprovalStore {
    override fun load(): List<ApprovalItem> = try {
        JsonFiles.readArray(file)?.map {
            val o = it as JsonObject
            ApprovalItem(
                JsonFiles.str(o, "id")!!, JsonFiles.str(o, "caller")!!, JsonFiles.str(o, "request_id")!!, JsonFiles.str(o, "hash")!!, JsonFiles.str(o, "capability")!!,
                JsonFiles.str(o, "summary").orEmpty(), JsonFiles.str(o, "bot"), JsonFiles.long(o, "created") ?: 0L, JsonFiles.long(o, "expires") ?: 0L,
                runCatching { ApprovalState.valueOf(JsonFiles.str(o, "state")!!) }.getOrDefault(ApprovalState.DENIED),
            )
        } ?: emptyList()
    } catch (_: Exception) {
        JsonFiles.quarantine(file)
        emptyList()
    }

    override fun save(items: Collection<ApprovalItem>) = JsonFiles.writeAtomic(
        file,
        CommsContract.JSON.encodeToString(
            JsonArray.serializer(),
            JsonArray(
                items.map { a ->
                    buildJsonObject {
                        put("id", a.id)
                        put("caller", a.caller)
                        put("request_id", a.requestId)
                        put("hash", a.payloadHash)
                        put("capability", a.capability)
                        put("summary", a.summary)
                        a.botId?.let { put("bot", it) }
                        put("created", a.createdAtMs)
                        put("expires", a.expiresAtMs)
                        put("state", a.state.name)
                    }
                },
            ),
        ),
    )
}

class JsonFileReceiptStore(private val file: Path, private val capacity: Int = 5_000) : ReceiptStore {
    private val lock = Any()
    private val cache = LinkedHashMap<String, Receipt>()
    override var healthy: Boolean = true
        private set

    init {
        try {
            JsonFiles.readArray(file)?.forEach {
                val o = it as JsonObject
                val r = Receipt(
                    JsonFiles.str(o, "caller")!!, JsonFiles.str(o, "request_id")!!, JsonFiles.str(o, "hash")!!, JsonFiles.str(o, "capability")!!,
                    CommsState.valueOf(JsonFiles.str(o, "state")!!), JsonFiles.str(o, "code").orEmpty(), JsonFiles.str(o, "detail").orEmpty(),
                    JsonFiles.str(o, "result") ?: "{}", JsonFiles.str(o, "job"), JsonFiles.long(o, "finished") ?: 0L,
                )
                cache["${r.caller}|${r.requestId}"] = r
            }
        } catch (_: Exception) {
            JsonFiles.quarantine(file)
            healthy = false // receipts were lost: a resend could repeat a send, so sends stop until the owner resets it
        }
    }

    override fun get(caller: String, requestId: String) = synchronized(lock) { cache["$caller|$requestId"] }

    override fun byJob(caller: String, jobId: String) = synchronized(lock) { cache.values.firstOrNull { it.caller == caller && it.jobId == jobId } }

    override fun putIfAbsent(r: Receipt): Receipt = synchronized(lock) {
        cache["${r.caller}|${r.requestId}"]?.let { return it }
        // Never drop evidence of what ran to make room: when full, stop accepting (the caller sees a refusal).
        if (cache.size >= capacity) return r.copy(state = CommsState.REFUSED, code = "receipt_store_full", detail = "Receipt store is full; nothing was recorded.")
        cache["${r.caller}|${r.requestId}"] = r
        JsonFiles.writeAtomic(
            file,
            CommsContract.JSON.encodeToString(
                JsonArray.serializer(),
                JsonArray(
                    cache.values.map { x ->
                        buildJsonObject {
                            put("caller", x.caller)
                            put("request_id", x.requestId)
                            put("hash", x.payloadHash)
                            put("capability", x.capability)
                            put("state", x.state.name)
                            put("code", x.code)
                            put("detail", x.detail)
                            put("result", x.resultJson)
                            x.jobId?.let { put("job", it) }
                            put("finished", x.finishedAtMs)
                        }
                    },
                ),
            ),
        )
        r
    }
}

/**
 * What the owner's own screen may do. The broker and the transport never receive this object: a request cannot reach it.
 * Every method here is for use from droidcommand-AI's UI only.
 */
class CommsAdmin(
    private val grants: GrantStore,
    private val approvals: ApprovalQueue,
    private val kill: KillSwitch,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(16) },
) {
    fun addGrant(
        caller: String,
        permissions: Set<CommsPermission>,
        botId: String? = null,
        contacts: Set<String>? = null,
        ttlMs: Long? = null,
        oneTime: Boolean = false,
    ): CommsGrant {
        require(caller.isNotBlank()) { "Choose an app." }
        require(permissions.isNotEmpty()) { "Choose at least one permission." }
        require(ttlMs == null || ttlMs > 0) { "A grant lifetime must be positive." }
        val g = CommsGrant(newId(), caller, botId?.takeIf { it.isNotBlank() }, contacts?.takeIf { it.isNotEmpty() }, permissions, ttlMs?.let { clock() + it }, oneTime, clock())
        grants.add(g)
        return g
    }

    fun grants(): List<CommsGrant> = grants.list().sortedByDescending { it.createdAtMs }

    fun revoke(id: String) = grants.revoke(id)

    fun revokeAll(caller: String) = grants.revokeAll(caller)

    fun pending(): List<ApprovalItem> = approvals.pending()

    fun approve(id: String) = approvals.decide(id, true)

    fun deny(id: String) = approvals.decide(id, false)

    val killEngaged get() = kill.engaged

    fun engageKill() = kill.engage()

    fun releaseKill() = kill.release()
}
