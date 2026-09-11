package ai.droidcommand.config

import ai.droidcommand.agent.CapabilityId
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingAuditLog : AuditLog {
    val events = mutableListOf<AuditEvent>()

    override fun record(event: AuditEvent): Boolean {
        events += event
        return true
    }
}

private class AlwaysFullAuditLog : AuditLog {
    override fun record(event: AuditEvent): Boolean = false
}

class InMemorySecretsVaultTest {
    @Test
    fun `putSecret then getSecret returns the value`() {
        val vault = InMemorySecretsVault(RecordingAuditLog())
        vault.putSecret("termux.package_manager:termux-api-token", "s3cr3t")

        assertEquals("s3cr3t", vault.getSecret("termux.package_manager:termux-api-token"))
    }

    @Test
    fun `getSecret for a never-stored id returns null`() {
        val vault = InMemorySecretsVault(RecordingAuditLog())
        assertNull(vault.getSecret("termux.package_manager:missing"))
    }

    @Test
    fun `revokeSecret removes a stored secret`() {
        val vault = InMemorySecretsVault(RecordingAuditLog())
        vault.putSecret("termux.package_manager:token", "s3cr3t")
        vault.revokeSecret("termux.package_manager:token")

        assertNull(vault.getSecret("termux.package_manager:token"))
    }

    @Test
    fun `listSecretIds returns only ids whose capability prefix matches, sorted`() {
        val vault = InMemorySecretsVault(RecordingAuditLog())
        vault.putSecret("termux.package_manager:zeta", "a")
        vault.putSecret("termux.package_manager:alpha", "b")
        vault.putSecret("shell.echo:other", "c")
        vault.revokeSecret("termux.package_manager:zeta")
        vault.putSecret("termux.package_manager:zeta", "d")

        assertEquals(
            listOf("termux.package_manager:alpha", "termux.package_manager:zeta"),
            vault.listSecretIds(CapabilityId("termux.package_manager")),
        )
    }

    @Test
    fun `a secretId with no colon never appears under any capability listing`() {
        val vault = InMemorySecretsVault(RecordingAuditLog())
        vault.putSecret("malformed-no-colon", "x")

        assertTrue(vault.listSecretIds(CapabilityId("malformed-no-colon")).isEmpty())
        assertTrue(vault.listSecretIds(CapabilityId("termux.package_manager")).isEmpty())
    }

    @Test
    fun `getSecret on a hit records exactly one SECRET_ACCESSED event naming the capability and secret, never the value`() {
        val log = RecordingAuditLog()
        val vault = InMemorySecretsVault(log)
        vault.putSecret("termux.package_manager:termux-api-token", "top-s3cr3t-value")

        vault.getSecret("termux.package_manager:termux-api-token")

        assertEquals(1, log.events.size)
        val event = log.events.single()
        assertEquals(AuditEventType.SECRET_ACCESSED, event.type)
        assertEquals("termux.package_manager:termux-api-token", event.subject)
        assertTrue(event.detail.contains("capability:termux.package_manager"))
        assertTrue(event.detail.contains("secret:termux-api-token"))
        assertTrue(event.detail.contains("success"))
        assertFalse(event.subject.contains("top-s3cr3t-value"))
        assertFalse(event.detail.contains("top-s3cr3t-value"))
    }

    @Test
    fun `getSecret on a miss still records an event, naming it not found`() {
        val log = RecordingAuditLog()
        val vault = InMemorySecretsVault(log)

        vault.getSecret("termux.package_manager:never-stored")

        val event = log.events.single()
        assertTrue(event.detail.contains("not found"))
    }

    @Test
    fun `a full audit log makes getSecret fail closed, returning null even for a genuinely stored secret`() {
        val vault = InMemorySecretsVault(AlwaysFullAuditLog())
        vault.putSecret("termux.package_manager:token", "s3cr3t")

        assertNull(vault.getSecret("termux.package_manager:token"))
    }
}
