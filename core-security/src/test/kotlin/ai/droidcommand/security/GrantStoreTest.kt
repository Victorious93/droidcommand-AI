package ai.droidcommand.security

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GrantStoreTest {
    @Test
    fun `an unknown grant id is denied`() {
        val store = InMemoryGrantStore()
        assertIs<GrantCheck.Denied>(store.check("does-not-exist", "root"))
    }

    @Test
    fun `a null grant id is denied without touching the store`() {
        val store = InMemoryGrantStore()
        assertIs<GrantCheck.Denied>(store.check(null, "root"))
    }

    @Test
    fun `a freshly issued grant for the right capability is live`() {
        val store = InMemoryGrantStore()
        store.issue(Grant(id = "g1", capability = "root"))
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
    }

    @Test
    fun `a grant for a different capability is denied`() {
        val store = InMemoryGrantStore()
        store.issue(Grant(id = "g1", capability = "remote_shell"))
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `a single-use grant is denied once consumed`() {
        val store = InMemoryGrantStore()
        store.issue(Grant(id = "g1", capability = "root", singleUse = true))
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
        store.consume("g1")
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `a multi-use grant stays live after being consumed`() {
        val store = InMemoryGrantStore()
        store.issue(Grant(id = "g1", capability = "root", singleUse = false))
        store.consume("g1")
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
    }

    @Test
    fun `a revoked grant is denied even if not expired or consumed`() {
        val store = InMemoryGrantStore()
        store.issue(Grant(id = "g1", capability = "root"))
        store.revoke("g1")
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `an expired grant is denied`() {
        val store = InMemoryGrantStore()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        store.issue(Grant(id = "g1", capability = "root", expiresAt = now.minus(1, ChronoUnit.SECONDS)))
        assertIs<GrantCheck.Denied>(store.check("g1", "root", now = now))
    }

    @Test
    fun `fails closed once at capacity, rather than evicting an older grant`() {
        val store = InMemoryGrantStore(capacity = 1)
        assert(store.issue(Grant(id = "g1", capability = "root")))
        assert(!store.issue(Grant(id = "g2", capability = "root")))
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
        assertIs<GrantCheck.Denied>(store.check("g2", "root"))
    }

    @Test
    fun `spent, revoked and expired grants no longer use up capacity`() {
        val store = InMemoryGrantStore(capacity = 3)
        assertTrue(store.issue(Grant(id = "spent", capability = "root", singleUse = true)))
        assertTrue(store.issue(Grant(id = "revoked", capability = "root")))
        assertTrue(store.issue(Grant(id = "expired", capability = "root", expiresAt = Instant.now().minus(1, ChronoUnit.HOURS))))
        store.consume("spent")
        store.revoke("revoked")

        // Three records are on file but none is live, so three fresh grants still fit...
        assertTrue(store.issue(Grant(id = "fresh-1", capability = "root")))
        assertTrue(store.issue(Grant(id = "fresh-2", capability = "root")))
        assertTrue(store.issue(Grant(id = "fresh-3", capability = "root")))
        // ...and only now is the store full.
        assertFalse(store.issue(Grant(id = "fresh-4", capability = "root")))
    }

    @Test
    fun `a consumed multi-use grant is still live, so it still uses up capacity`() {
        val store = InMemoryGrantStore(capacity = 1)
        assertTrue(store.issue(Grant(id = "g1", capability = "root", singleUse = false)))
        store.consume("g1")
        assertFalse(store.issue(Grant(id = "g2", capability = "root")))
    }

    @Test
    fun `issuing an id that was already issued is refused, so a revoked grant stays revoked`() {
        val store = InMemoryGrantStore()
        assertTrue(store.issue(Grant(id = "g1", capability = "root")))
        assertFalse(store.issue(Grant(id = "g1", capability = "root"))) // still live: not silently replaced
        store.revoke("g1")
        assertFalse(store.issue(Grant(id = "g1", capability = "root")))
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `issuing the id of a spent single-use grant is refused, so it cannot be spent twice`() {
        val store = InMemoryGrantStore()
        assertTrue(store.issue(Grant(id = "g1", capability = "root", singleUse = true)))
        store.consume("g1")
        assertFalse(store.issue(Grant(id = "g1", capability = "root", singleUse = true)))
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }
}
