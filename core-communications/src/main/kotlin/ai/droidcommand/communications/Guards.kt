package ai.droidcommand.communications

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A standing authorisation the OWNER created in droidcommand-AI's own UI. Scoped to an app, optionally a bot and a
 * set of contacts, optionally expiring, optionally single-use. There is deliberately no request type, in any
 * contract version, that creates, widens or extends a grant: only the owner-side [GrantStore.add] does.
 */
data class CommsGrant(
    val id: String,
    val caller: String,
    /** Null means any bot of that app. */
    val botId: String?,
    /** Null means any contact. Otherwise keys as produced by [CommsCatalog.targets] (`to:<digits>`, `c:<conversation>`). */
    val contacts: Set<String>?,
    val permissions: Set<CommsPermission>,
    val expiresAtMs: Long?,
    val oneTime: Boolean,
    val createdAtMs: Long,
    val revoked: Boolean = false,
    val uses: Int = 0,
)

interface GrantStore {
    fun add(grant: CommsGrant)
    fun list(): List<CommsGrant>
    fun revoke(id: String): Boolean
    fun revokeAll(caller: String): Int
    fun markUsed(id: String)
}

class InMemoryGrantStore : GrantStore {
    private val map = LinkedHashMap<String, CommsGrant>()

    @Synchronized override fun add(grant: CommsGrant) {
        map[grant.id] = grant
    }

    @Synchronized override fun list() = map.values.toList()

    @Synchronized override fun revoke(id: String): Boolean = map[id]?.let {
        map[id] = it.copy(revoked = true)
        true
    } ?: false

    @Synchronized override fun revokeAll(caller: String): Int = map.values.filter { it.caller == caller && !it.revoked }.onEach { map[it.id] = it.copy(revoked = true) }.size

    @Synchronized override fun markUsed(id: String) {
        map[id]?.let { map[id] = it.copy(uses = it.uses + 1) }
    }
}

sealed class AuthDecision {
    data class Allowed(val grantIds: List<String>, val autoReply: Boolean) : AuthDecision()
    data class Denied(val missing: Set<CommsPermission>) : AuthDecision()
}

class Authorizer(private val grants: GrantStore, private val clock: () -> Long) {
    private fun usable(g: CommsGrant, caller: String, botId: String?, targets: Set<String>): Boolean =
        !g.revoked && g.caller == caller && (g.expiresAtMs == null || clock() < g.expiresAtMs) && !(g.oneTime && g.uses > 0) &&
            (g.botId == null || g.botId == botId) && (g.contacts == null || targets.all { it in g.contacts })

    /** Every needed permission must be covered by some usable grant for this caller, bot and every named contact. */
    fun check(caller: String, botId: String?, targets: Set<String>, needed: Set<CommsPermission>): AuthDecision {
        val live = grants.list().filter { usable(it, caller, botId, targets) }
        val used = LinkedHashSet<String>()
        val missing = needed.filter { p ->
            val g = live.firstOrNull { p in it.permissions }
            if (g != null) used.add(g.id)
            g == null
        }
        if (missing.isNotEmpty()) return AuthDecision.Denied(missing.toSet())
        return AuthDecision.Allowed(used.toList(), autoReply = hasAutoReply(caller, botId, targets))
    }

    /** The one grant that lets a caller's claim of `mode: auto` stand. Without it, an auto claim is treated as approval-required. */
    fun hasAutoReply(caller: String, botId: String?, targets: Set<String>): Boolean =
        botId != null && grants.list().any { usable(it, caller, botId, targets) && CommsPermission.ENABLE_AUTO_REPLY in it.permissions && it.botId == botId }

    fun consume(ids: List<String>) {
        ids.forEach { id -> grants.list().firstOrNull { it.id == id }?.let { if (it.oneTime) grants.markUsed(id) } }
    }
}

enum class ApprovalState { PENDING, APPROVED, DENIED, EXPIRED, USED }

data class ApprovalItem(
    val id: String, val caller: String, val requestId: String, val payloadHash: String, val capability: String,
    val summary: String, val botId: String?, val createdAtMs: Long, val expiresAtMs: Long, val state: ApprovalState,
)

/**
 * The owner's approval queue. [submit] is the only thing a request can do; [decide] is called from the owner's own
 * screen. An approval is bound to the exact caller, request id, capability and arguments (by hash).
 */
class ApprovalQueue(private val clock: () -> Long) {
    private val items = LinkedHashMap<String, ApprovalItem>()

    @Synchronized fun submit(caller: String, requestId: String, payloadHash: String, capability: String, summary: String, botId: String?, expiresAtMs: Long): ApprovalItem {
        forRequest(caller, requestId)?.let { return it }
        val id = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return ApprovalItem(id, caller, requestId, payloadHash, capability, summary.take(300), botId, clock(), expiresAtMs, ApprovalState.PENDING).also { items[id] = it }
    }

    @Synchronized fun forRequest(caller: String, requestId: String): ApprovalItem? =
        items.values.firstOrNull { it.caller == caller && it.requestId == requestId }?.let(::expireIfNeeded)

    @Synchronized fun pending(): List<ApprovalItem> = items.values.map(::expireIfNeeded).filter { it.state == ApprovalState.PENDING }

    /** Owner action only. Returns false when the item is unknown or no longer pending. */
    @Synchronized fun decide(id: String, approve: Boolean): Boolean {
        val cur = items[id]?.let(::expireIfNeeded) ?: return false
        if (cur.state != ApprovalState.PENDING) return false
        items[id] = cur.copy(state = if (approve) ApprovalState.APPROVED else ApprovalState.DENIED)
        return true
    }

    /** Atomically turns APPROVED into USED so one approval can authorise exactly one execution. */
    @Synchronized fun consume(id: String): Boolean {
        val cur = items[id]?.let(::expireIfNeeded) ?: return false
        if (cur.state != ApprovalState.APPROVED) return false
        items[id] = cur.copy(state = ApprovalState.USED)
        return true
    }

    private fun expireIfNeeded(i: ApprovalItem): ApprovalItem {
        if ((i.state == ApprovalState.PENDING || i.state == ApprovalState.APPROVED) && clock() >= i.expiresAtMs) {
            val e = i.copy(state = ApprovalState.EXPIRED)
            items[i.id] = e
            return e
        }
        return i
    }
}

/** Remembers recently seen nonces. A nonce can be used once, ever, inside its validity window. */
class ReplayGuard(private val clock: () -> Long, private val windowMs: Long = 10 * 60_000L, private val capacity: Int = 20_000) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized fun firstUse(caller: String, nonce: String): Boolean {
        val now = clock()
        val it = seen.entries.iterator()
        while (it.hasNext() && (seen.size > capacity || now - it.next().value > windowMs)) it.remove()
        return seen.putIfAbsent("$caller|$nonce", now) == null
    }
}

class RateLimiter(private val clock: () -> Long, private val perMinute: (CommsRisk) -> Int = ::defaultLimit) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun allow(caller: String, risk: CommsRisk): Boolean {
        val q = hits.getOrPut("$caller|${risk.name}") { ArrayDeque() }
        synchronized(q) {
            val now = clock()
            while (q.isNotEmpty() && now - q.first() >= 60_000) q.removeFirst()
            if (q.size >= perMinute(risk)) return false
            q.addLast(now)
            return true
        }
    }

    companion object {
        fun defaultLimit(r: CommsRisk) = when (r) {
            CommsRisk.READ -> 120
            CommsRisk.DRAFT, CommsRisk.WRITE -> 30
            CommsRisk.SEND, CommsRisk.CALL -> 10
            CommsRisk.ADMIN -> 10
            CommsRisk.SAFE -> 60
        }
    }
}

/** Emergency stop. While on, nothing that sends, calls or enables automation runs; reading, pausing and cancelling still do. */
class KillSwitch {
    private val on = AtomicBoolean(false)
    val engaged get() = on.get()
    fun engage() = on.set(true)
    fun release() = on.set(false)
}

enum class CommsState { COMPLETED, RUNNING, FAILED, REFUSED, UNKNOWN }

data class Receipt(
    val caller: String, val requestId: String, val payloadHash: String, val capability: String, val state: CommsState,
    val code: String, val detail: String, val resultJson: String, val jobId: String?, val finishedAtMs: Long,
)

interface ReceiptStore {
    fun get(caller: String, requestId: String): Receipt?
    fun putIfAbsent(r: Receipt): Receipt
    fun byJob(caller: String, jobId: String): Receipt?
}

class InMemoryReceiptStore : ReceiptStore {
    private val map = LinkedHashMap<String, Receipt>()

    @Synchronized override fun get(caller: String, requestId: String) = map["$caller|$requestId"]

    @Synchronized override fun putIfAbsent(r: Receipt): Receipt = map.getOrPut("${r.caller}|${r.requestId}") { r }

    @Synchronized override fun byJob(caller: String, jobId: String) = map.values.firstOrNull { it.caller == caller && it.jobId == jobId }
}

object Hashes {
    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
