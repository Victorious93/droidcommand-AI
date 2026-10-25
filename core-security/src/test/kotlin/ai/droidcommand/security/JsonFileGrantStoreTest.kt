package ai.droidcommand.security

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun newStore(capacity: Int = 10_000) = JsonFileGrantStore(Files.createTempDirectory("droidcommand-grant-store-test"), capacity)

class JsonFileGrantStoreTest {
    @Test
    fun `an unknown grant id is denied`() {
        assertIs<GrantCheck.Denied>(newStore().check("does-not-exist", "root"))
    }

    @Test
    fun `a null grant id is denied without touching the store`() {
        assertIs<GrantCheck.Denied>(newStore().check(null, "root"))
    }

    @Test
    fun `a freshly issued grant for the right capability is live`() {
        val store = newStore()
        store.issue(Grant(id = "g1", capability = "root"))
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
    }

    @Test
    fun `a grant for a different capability is denied`() {
        val store = newStore()
        store.issue(Grant(id = "g1", capability = "remote_shell"))
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `a single-use grant is denied once consumed`() {
        val store = newStore()
        store.issue(Grant(id = "g1", capability = "root", singleUse = true))
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
        store.consume("g1")
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `a multi-use grant stays live after being consumed`() {
        val store = newStore()
        store.issue(Grant(id = "g1", capability = "root", singleUse = false))
        store.consume("g1")
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
    }

    @Test
    fun `a revoked grant is denied even if not expired or consumed`() {
        val store = newStore()
        store.issue(Grant(id = "g1", capability = "root"))
        store.revoke("g1")
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `an expired grant is denied`() {
        val store = newStore()
        val now = Instant.parse("2026-01-01T00:00:00Z")
        store.issue(Grant(id = "g1", capability = "root", expiresAt = now.minus(1, ChronoUnit.SECONDS)))
        assertIs<GrantCheck.Denied>(store.check("g1", "root", now = now))
    }

    @Test
    fun `fails closed once at capacity, rather than evicting an older grant`() {
        val store = newStore(capacity = 1)
        assertTrue(store.issue(Grant(id = "g1", capability = "root")))
        assertFalse(store.issue(Grant(id = "g2", capability = "root")))
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
        assertIs<GrantCheck.Denied>(store.check("g2", "root"))
    }

    @Test
    fun `revocation survives a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-grant-store-test")
        JsonFileGrantStore(dir).apply {
            issue(Grant(id = "g1", capability = "root"))
            revoke("g1")
        }

        val reopened = JsonFileGrantStore(dir)
        assertIs<GrantCheck.Denied>(reopened.check("g1", "root"))
    }

    @Test
    fun `consumption of a single-use grant survives a fresh store instance`() {
        val dir = Files.createTempDirectory("droidcommand-grant-store-test")
        JsonFileGrantStore(dir).apply {
            issue(Grant(id = "g1", capability = "root", singleUse = true))
            consume("g1")
        }

        val reopened = JsonFileGrantStore(dir)
        assertIs<GrantCheck.Denied>(reopened.check("g1", "root"))
    }

    @Test
    fun `a path-traversal-shaped grant id is rejected`() {
        val store = newStore()
        assertFailsWith<InvalidGrantId> { store.issue(Grant(id = "../escape", capability = "root")) }
    }

    @Test
    fun `an absolute-path-shaped grant id is rejected`() {
        val store = newStore()
        assertFailsWith<InvalidGrantId> { store.issue(Grant(id = "/etc/passwd", capability = "root")) }
    }

    @Test
    fun `consume and revoke are no-ops for an unknown grant id`() {
        val store = newStore()
        store.consume("does-not-exist")
        store.revoke("does-not-exist")
        assertIs<GrantCheck.Denied>(store.check("does-not-exist", "root"))
    }

    @Test
    fun `check, consume and revoke treat an id that could never have been issued as unknown instead of throwing`() {
        val store = newStore()
        for (id in listOf("../escape", "/etc/passwd", "with space", "", "a/b", "nul\u0000byte")) {
            assertIs<GrantCheck.Denied>(store.check(id, "root"), "check('$id')")
            store.consume(id)
            store.revoke(id)
        }
    }

    @Test
    fun `spent, revoked and expired grants no longer use up capacity`() {
        val store = newStore(capacity = 3)
        assertTrue(store.issue(Grant(id = "spent", capability = "root", singleUse = true)))
        assertTrue(store.issue(Grant(id = "revoked", capability = "root")))
        assertTrue(store.issue(Grant(id = "expired", capability = "root", expiresAt = Instant.now().minus(1, ChronoUnit.HOURS))))
        store.consume("spent")
        store.revoke("revoked")

        // Three records are on disk but none is live, so three fresh grants still fit...
        assertTrue(store.issue(Grant(id = "fresh-1", capability = "root")))
        assertTrue(store.issue(Grant(id = "fresh-2", capability = "root")))
        assertTrue(store.issue(Grant(id = "fresh-3", capability = "root")))
        // ...and only now is the store full.
        assertFalse(store.issue(Grant(id = "fresh-4", capability = "root")))
    }

    @Test
    fun `a consumed multi-use grant is still live, so it still uses up capacity`() {
        val store = newStore(capacity = 1)
        assertTrue(store.issue(Grant(id = "g1", capability = "root", singleUse = false)))
        store.consume("g1")
        assertFalse(store.issue(Grant(id = "g2", capability = "root")))
    }

    @Test
    fun `a record that cannot be read still uses up capacity`() {
        val dir = Files.createTempDirectory("droidcommand-grant-store-test")
        Files.writeString(dir.resolve("broken.grant.json"), "{ this is not json")
        val store = JsonFileGrantStore(dir, capacity = 1)
        assertFalse(store.issue(Grant(id = "g1", capability = "root")))
    }

    @Test
    fun `issuing an id that was already issued is refused, so a revoked grant stays revoked`() {
        val store = newStore()
        assertTrue(store.issue(Grant(id = "g1", capability = "root")))
        store.revoke("g1")
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
        assertFalse(store.issue(Grant(id = "g1", capability = "root")))
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `issuing the id of a spent single-use grant is refused, so it cannot be spent twice`() {
        val store = newStore()
        assertTrue(store.issue(Grant(id = "g1", capability = "root", singleUse = true)))
        store.consume("g1")
        assertFalse(store.issue(Grant(id = "g1", capability = "root", singleUse = true)))
        assertIs<GrantCheck.Denied>(store.check("g1", "root"))
    }

    @Test
    fun `a refused re-issue leaves the original record untouched`() {
        val store = newStore()
        assertTrue(store.issue(Grant(id = "g1", capability = "root")))
        assertFalse(store.issue(Grant(id = "g1", capability = "remote_shell")))
        assertIs<GrantCheck.Live>(store.check("g1", "root"))
        assertIs<GrantCheck.Denied>(store.check("g1", "remote_shell"))
    }
}
