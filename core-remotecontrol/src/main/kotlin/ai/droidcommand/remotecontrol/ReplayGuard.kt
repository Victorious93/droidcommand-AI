package ai.droidcommand.remotecontrol

import kotlin.math.abs

/**
 * Replay cache keyed by `(sourceNodeRef, nonce)`.
 *
 * [sourceNodeRef] is the **transport** identity (paired node / companion /
 * Termux), never [TriggerInfo]. Retention is ≥ 10 minutes.
 *
 * Reject:
 * - `now > timestampMs + ttlMs` → [ReplayDecision.Reject.code] `expired`
 * - `|now - timestampMs| > 120000` → `expired`
 * - nonce seen for that source → `replay_detected`
 * - optional last-accepted timestamp: older than last for that source → `expired`
 */
class ReplayGuard(
    private val retentionMs: Long = FleetRemoteEnvelope.REPLAY_RETENTION_MS,
    private val skewMs: Long = FleetRemoteEnvelope.SKEW_MS,
    private val maxNonces: Int = DEFAULT_MAX_NONCES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val seen = LinkedHashMap<SeenKey, Long>()
    private val lastAccepted = HashMap<String, Long>()

    fun check(envelope: FleetRemoteEnvelope, source: SourceIdentity): ReplayDecision =
        check(source.sourceNodeRef, envelope.nonce, envelope.timestampMs, envelope.ttlMs)

    fun check(
        sourceNodeRef: String,
        nonce: String,
        timestampMs: Long,
        ttlMs: Long = FleetRemoteEnvelope.DEFAULT_TTL_MS,
    ): ReplayDecision {
        require(sourceNodeRef.isNotBlank()) { "sourceNodeRef is required" }
        val ttl = ttlMs.coerceIn(1L, FleetRemoteEnvelope.MAX_TTL_MS)
        val now = nowMs()
        if (now > timestampMs + ttl) {
            return ReplayDecision.Reject("expired", "expired")
        }
        if (abs(now - timestampMs) > skewMs) {
            return ReplayDecision.Reject("expired", "expired")
        }
        val key = SeenKey(sourceNodeRef, nonce)
        synchronized(lock) {
            evictLocked(now)
            if (key in seen) {
                return ReplayDecision.Reject("replay_detected", "replay_detected")
            }
            val last = lastAccepted[sourceNodeRef]
            if (last != null && timestampMs < last) {
                return ReplayDecision.Reject("expired", "expired")
            }
            seen[key] = now
            lastAccepted[sourceNodeRef] = timestampMs
            while (seen.size > maxNonces) {
                val oldest = seen.keys.first()
                seen.remove(oldest)
            }
        }
        return ReplayDecision.Accept
    }

    fun seenCount(): Int = synchronized(lock) { seen.size }

    private fun evictLocked(now: Long) {
        val cutoff = now - retentionMs
        val stale = seen.entries.filter { it.value < cutoff }.map { it.key }
        stale.forEach { seen.remove(it) }
    }

    private data class SeenKey(val sourceNodeRef: String, val nonce: String)

    companion object {
        const val DEFAULT_MAX_NONCES = 4096
    }
}

sealed class ReplayDecision {
    data object Accept : ReplayDecision()
    data class Reject(val reason: String, val code: String) : ReplayDecision()
}
