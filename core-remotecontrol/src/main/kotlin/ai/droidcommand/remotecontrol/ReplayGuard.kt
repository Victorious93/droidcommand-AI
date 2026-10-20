package ai.droidcommand.remotecontrol

/**
 * Seen-nonce cache keyed by ([SourceIdentity.sourceNodeRef], nonce).
 * Retention ≥ 10 minutes. Duplicate (source, nonce) → replay_detected.
 * The same nonce from a different source is a distinct key.
 *
 * Reject:
 * - seen (sourceNodeRef, nonce) → [RemoteErrorCategory.REPLAY_DETECTED]
 * - `now > timestampMs + ttlMs` → [RemoteErrorCategory.EXPIRED]
 * - `timestampMs > now + 120000` → [RemoteErrorCategory.EXPIRED]
 *   (future-skew allowance only — not a two-sided cap)
 *
 * Past validity is TTL-only, so the full 300s max TTL is usable.
 */
class ReplayGuard(
    private val retentionMs: Long = FleetRemoteEnvelope.REPLAY_RETENTION_MS,
    private val futureSkewMs: Long = FleetRemoteEnvelope.FUTURE_SKEW_MS,
    private val maxNonces: Int = DEFAULT_MAX_NONCES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val seen = LinkedHashMap<SeenNonce, Long>()

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
            return ReplayDecision.Reject(RemoteErrorCategory.EXPIRED)
        }
        if (timestampMs > now + futureSkewMs) {
            return ReplayDecision.Reject(RemoteErrorCategory.EXPIRED)
        }
        val key = SeenNonce(sourceNodeRef, nonce)
        synchronized(lock) {
            evictLocked(now)
            if (key in seen) {
                return ReplayDecision.Reject(RemoteErrorCategory.REPLAY_DETECTED)
            }
            seen[key] = now
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

    private data class SeenNonce(val sourceNodeRef: String, val nonce: String)

    companion object {
        const val DEFAULT_MAX_NONCES = 4096
    }
}

sealed class ReplayDecision {
    data object Accept : ReplayDecision()
    data class Reject(val category: RemoteErrorCategory) : ReplayDecision() {
        val reason: String get() = category.wire
        val code: String get() = category.wire
    }
}
