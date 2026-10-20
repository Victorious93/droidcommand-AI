package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReplayGuardTest {
    @Test
    fun `accepts a fresh unused nonce`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val first = guard.check(envelope(issuedAt = now))
        assertEquals(ReplayDecision.Accept, first)
        assertEquals(1, guard.seenCount())
    }

    @Test
    fun `same nonce from two sources is replay_detected`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(ReplayDecision.Accept, guard.check(envelope(issuedAt = now), source("node-a")))
        val second = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(issuedAt = now, nonce = TEST_NONCE), source("node-b")),
        )
        assertEquals(RemoteErrorCategory.REPLAY_DETECTED, second.category)
        assertEquals("replay_detected", second.code)
    }

    @Test
    fun `rejects a reused nonce as replay_detected`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        guard.check(envelope(issuedAt = now))
        val second = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(issuedAt = now + 10, nonce = TEST_NONCE)),
        )
        assertEquals(RemoteErrorCategory.REPLAY_DETECTED, second.category)
    }

    @Test
    fun `rejects when now is past issuedAt plus ttl as expired`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val decision = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(issuedAt = now - 120_001, ttlMs = 120_000)),
        )
        assertEquals(RemoteErrorCategory.EXPIRED, decision.category)
        assertEquals("expired", decision.code)
        assertEquals(0, guard.seenCount())
    }

    @Test
    fun `rejects clock skew beyond 120 seconds as expired`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val future = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(issuedAt = now + 120_001)),
        )
        assertEquals(RemoteErrorCategory.EXPIRED, future.category)
    }

    @Test
    fun `evicts entries older than ten minutes so the nonce can be reused`() {
        var now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(ReplayDecision.Accept, guard.check(envelope(issuedAt = now)))
        now += FleetRemoteEnvelope.REPLAY_RETENTION_MS + 1
        assertEquals(
            ReplayDecision.Accept,
            guard.check(envelope(nonce = TEST_NONCE, issuedAt = now)),
        )
        assertTrue(guard.seenCount() <= 2)
    }
}
