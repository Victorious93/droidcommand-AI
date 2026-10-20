package ai.droidcommand.remotecontrol

import kotlin.math.abs

/**
 * Seen-nonce cache for the TTL window. RATIFIED: duplicate nonce is a
 * replay regardless of source.
 *
 * Reject:
 * - nonce already seen → [RemoteErrorCategory.REPLAY_DETECTED]
 * - `now > issuedAt + ttlMs` → [RemoteErrorCategory.EXPIRED]
 * - `|now - issuedAt| > 120000` → [RemoteErrorCategory.EXPIRED] (clock skew)
 */
class ReplayGuard(
    private val retentionMs: Long = FleetRemoteEnvelope.REPLAY_RETENTION_MS,
    private val skewMs: Long = FleetRemoteEnvelope.SKEW_MS,
    private val maxNonces: Int = DEFAULT_MAX_NONCES,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private val seen = LinkedHashMap<String, Long>()

    fun check(envelope: FleetRemoteEnvelope): ReplayDecision = check(envelope.auth)

    @Suppress("UNUSED_PARAMETER")
    fun check(envelope: FleetRemoteEnvelope, source: SourceIdentity): ReplayDecision = check(envelope.auth)

    fun check(auth: EnvelopeAuth): ReplayDecision = check(auth.nonce, auth.issuedAt, auth.ttlMs)

    fun check(
        nonce: String,
        issuedAt: Long,
        ttlMs: Long = FleetRemoteEnvelope.DEFAULT_TTL_MS,
    ): ReplayDecision {
        val ttl = ttlMs.coerceIn(1L, FleetRemoteEnvelope.MAX_TTL_MS)
        val now = nowMs()
        if (now > issuedAt + ttl) {
            return ReplayDecision.Reject(RemoteErrorCategory.EXPIRED)
        }
        if (abs(now - issuedAt) > skewMs) {
            return ReplayDecision.Reject(RemoteErrorCategory.EXPIRED)
        }
        synchronized(lock) {
            evictLocked(now)
            if (nonce in seen) {
                return ReplayDecision.Reject(RemoteErrorCategory.REPLAY_DETECTED)
            }
            seen[nonce] = now
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
