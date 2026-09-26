package ai.droidcommand.remote.pairing

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthGateTest {
    private val clock = MutableClock(Instant.parse("2026-09-26T12:00:00Z"))
    private val gate = AuthGate(clock = clock)

    @Test
    fun `locks a peer out after five failures within the window`() {
        repeat(4) { gate.recordFailure("peer") }
        assertFalse(gate.isLocked("peer"))
        gate.recordFailure("peer")
        assertEquals(Instant.parse("2026-09-26T12:05:00Z"), gate.lockedUntil("peer"))
    }

    @Test
    fun `the lockout expires after five minutes`() {
        repeat(5) { gate.recordFailure("peer") }
        clock.advance(Duration.ofMinutes(5).minusSeconds(1))
        assertTrue(gate.isLocked("peer"))
        clock.advance(Duration.ofSeconds(1))
        assertFalse(gate.isLocked("peer"))
    }

    @Test
    fun `failures older than the window do not count`() {
        repeat(4) { gate.recordFailure("peer") }
        clock.advance(Duration.ofSeconds(61))
        gate.recordFailure("peer")
        assertFalse(gate.isLocked("peer"))
    }

    @Test
    fun `a success clears the failure history`() {
        repeat(4) { gate.recordFailure("peer") }
        gate.recordSuccess("peer")
        gate.recordFailure("peer")
        assertFalse(gate.isLocked("peer"))
    }

    @Test
    fun `peers are tracked independently`() {
        repeat(5) { gate.recordFailure("attacker") }
        assertTrue(gate.isLocked("attacker"))
        assertFalse(gate.isLocked("owner"))
    }
}
