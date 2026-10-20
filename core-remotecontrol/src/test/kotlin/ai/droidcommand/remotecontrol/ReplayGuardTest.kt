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
        val first = guard.check(envelope(timestampMs = now), source())
        assertEquals(ReplayDecision.Accept, first)
        assertEquals(1, guard.seenCount())
    }

    @Test
    fun `same nonce from two sources is accepted because the cache is keyed by source and nonce`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(ReplayDecision.Accept, guard.check(envelope(timestampMs = now), source("node-a")))
        assertEquals(
            ReplayDecision.Accept,
            guard.check(envelope(timestampMs = now, nonce = TEST_NONCE), source("node-b")),
        )
        assertEquals(2, guard.seenCount())
    }

    @Test
    fun `rejects a reused nonce from the same source as replay_detected`() {
        val now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        guard.check(envelope(timestampMs = now), source())
        val second = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(timestampMs = now + 10, nonce = TEST_NONCE), source()),
        )
        assertEquals(RemoteErrorCategory.REPLAY_DETECTED, second.category)
        assertEquals("replay_detected", second.code)
    }

    @Test
    fun `rejects when now is past timestampMs plus ttl as expired`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val decision = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(timestampMs = now - 120_001, ttlMs = 120_000), source()),
        )
        assertEquals(RemoteErrorCategory.EXPIRED, decision.category)
        assertEquals("expired", decision.code)
        assertEquals(0, guard.seenCount())
    }

    @Test
    fun `accepts a 200s-old timestamp when ttl still covers it`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(
            ReplayDecision.Accept,
            guard.check(
                envelope(timestampMs = now - 200_000, ttlMs = 300_000),
                source(),
            ),
        )
        assertEquals(1, guard.seenCount())
    }

    @Test
    fun `rejects a timestamp 200s in the future as expired`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val future = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(timestampMs = now + 200_000), source()),
        )
        assertEquals(RemoteErrorCategory.EXPIRED, future.category)
        assertEquals("expired", future.code)
        assertEquals(0, guard.seenCount())
    }

    @Test
    fun `rejects future skew beyond 120 seconds as expired`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        val future = assertIs<ReplayDecision.Reject>(
            guard.check(envelope(timestampMs = now + 120_001), source()),
        )
        assertEquals(RemoteErrorCategory.EXPIRED, future.category)
    }

    @Test
    fun `accepts a timestamp 100s in the future within the skew allowance`() {
        val now = 10_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(
            ReplayDecision.Accept,
            guard.check(envelope(timestampMs = now + 100_000), source()),
        )
    }

    @Test
    fun `evicts entries older than ten minutes so the nonce can be reused`() {
        var now = 1_000_000L
        val guard = ReplayGuard(nowMs = { now })
        assertEquals(ReplayDecision.Accept, guard.check(envelope(timestampMs = now), source()))
        now += FleetRemoteEnvelope.REPLAY_RETENTION_MS + 1
        assertEquals(
            ReplayDecision.Accept,
            guard.check(envelope(nonce = TEST_NONCE, timestampMs = now), source()),
        )
        assertTrue(guard.seenCount() <= 2)
    }
}
