package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReplayGuardTest {
    @Test
    fun `accepts a fresh unused nonce for a source`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val first = guard.check(envelope(timestampMs = now), source())
        assertEquals(ReplayDecision.Accept, first)
        assertEquals(1, guard.seenCount())
    }

    @Test
    fun `same nonce from two sources is not a replay`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(ReplayDecision.Accept, guard.check(envelope(timestampMs = now), source("node-a")))
        assertEquals(
            ReplayDecision.Accept,
            guard.check(envelope(timestampMs = now, nonce = TEST_NONCE), source("node-b")),
        )
    }

    @Test
    fun `rejects a reused nonce for the same source as replay_detected`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        guard.check(envelope(timestampMs = now), source())
        val second = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(timestampMs = now + 10, nonce = TEST_NONCE), source()),
        )
        assertEquals("replay_detected", second.code)
    }

    @Test
    fun `rejects when now is past timestamp plus ttl as expired`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val decision = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(timestampMs = now - 120_001, ttlMs = 120_000), source()),
        )
        assertEquals("expired", decision.code)
        assertEquals(0, guard.seenCount())
    }

    @Test
    fun `rejects clock skew beyond 120 seconds as expired`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val future = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(timestampMs = now + 120_001), source()),
        )
        assertEquals("expired", future.code)
    }

    @Test
    fun `drops a stale replay older than last accepted for that source`() {
        val now = 5_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(ReplayDecision.Accept, guard.check(envelope(nonce = TEST_NONCE, timestampMs = now), source()))
        val stale = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(nonce = TEST_NONCE_B, timestampMs = now - 1), source()),
        )
        assertEquals("expired", stale.code)
    }

    @Test
    fun `evicts entries older than ten minutes`() {
        var now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(ReplayDecision.Accept, guard.check(envelope(timestampMs = now), source()))
        now += FleetRemoteEnvelope.REPLAY_RETENTION_MS + 1
        assertEquals(
            ReplayDecision.Accept,
            guard.check(envelope(nonce = TEST_NONCE_B, timestampMs = now), source()),
        )
        assertTrue(guard.seenCount() <= 2)
        assertEquals(
            ReplayDecision.Accept,
            guard.check(envelope(nonce = TEST_NONCE, timestampMs = now), source("other")),
        )
    }
}
