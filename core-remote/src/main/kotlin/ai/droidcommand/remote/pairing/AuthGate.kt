package ai.droidcommand.remote.pairing

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Brute-force protection for pairing: after [maxFailures] failed handshakes
 * from one peer within [failureWindow], that peer is locked out for
 * [lockoutDuration] — no handshake from it is even attempted until the
 * lockout expires. Defaults are 5 failures in 60 seconds, then 5 minutes.
 *
 * Keyed by an opaque peer id the caller chooses (a remote address, a device
 * id). A success clears that peer's failure history. Thread-safe.
 */
class AuthGate(
    private val maxFailures: Int = 5,
    private val failureWindow: Duration = Duration.ofSeconds(60),
    private val lockoutDuration: Duration = Duration.ofMinutes(5),
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(maxFailures >= 1) { "maxFailures must be at least 1" }
        require(!failureWindow.isNegative && !failureWindow.isZero) { "failureWindow must be positive" }
        require(!lockoutDuration.isNegative && !lockoutDuration.isZero) { "lockoutDuration must be positive" }
    }

    private val failures = mutableMapOf<String, ArrayDeque<Instant>>()
    private val lockedUntil = mutableMapOf<String, Instant>()

    /** When [peerId] is locked out, the instant its lockout ends; otherwise null. */
    @Synchronized
    fun lockedUntil(peerId: String): Instant? {
        val until = lockedUntil[peerId] ?: return null
        if (!clock.instant().isBefore(until)) {
            lockedUntil.remove(peerId)
            return null
        }
        return until
    }

    fun isLocked(peerId: String): Boolean = lockedUntil(peerId) != null

    @Synchronized
    fun recordFailure(peerId: String) {
        val now = clock.instant()
        val recent = failures.getOrPut(peerId) { ArrayDeque() }
        recent.addLast(now)
        val windowStart = now.minus(failureWindow)
        while (recent.isNotEmpty() && !recent.first().isAfter(windowStart)) recent.removeFirst()
        if (recent.size >= maxFailures) {
            lockedUntil[peerId] = now.plus(lockoutDuration)
            failures.remove(peerId)
        }
    }

    @Synchronized
    fun recordSuccess(peerId: String) {
        failures.remove(peerId)
        lockedUntil.remove(peerId)
    }
}
