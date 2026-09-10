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
}
