package ai.droidcommand.config

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.CapabilityId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingAuditLog : AuditLog {
    val events = mutableListOf<AuditEvent>()
    override fun record(event: AuditEvent): Boolean {
        events += event
        return true
    }
}

class SecretsVaultTest {
    @Test
    fun `getSecret returns null for an id that was never put`() {
        val vault = InMemorySecretsVault()
        assertNull(vault.getSecret("does-not-exist"))
    }

    @Test
    fun `putSecret then getSecret returns the value`() {
        val vault = InMemorySecretsVault()
        vault.putSecret("termux-api-token", "s3cr3t")
        assertEquals("s3cr3t", vault.getSecret("termux-api-token"))
    }

    @Test
    fun `revokeSecret removes the value so a later getSecret returns null`() {
        val vault = InMemorySecretsVault()
        vault.putSecret("termux-api-token", "s3cr3t")

        vault.revokeSecret("termux-api-token")

        assertNull(vault.getSecret("termux-api-token"))
    }

    @Test
    fun `revoking an unknown secret is a no-op and records nothing`() {
        val auditLog = RecordingAuditLog()
        val vault = InMemorySecretsVault(auditLog)

        vault.revokeSecret("never-existed")

        assertTrue(auditLog.events.isEmpty())
    }

    @Test
    fun `listSecretIds returns only ids registered under that capability`() {
        val vault = InMemorySecretsVault()
        vault.putSecret("token-a", "a", capabilityId = CapabilityId("termux.package_manager"))
        vault.putSecret("token-b", "b", capabilityId = CapabilityId("termux.package_manager"))
        vault.putSecret("token-c", "c", capabilityId = CapabilityId("remote.ssh"))
        vault.putSecret("token-unscoped", "u")

        val ids = vault.listSecretIds(CapabilityId("termux.package_manager"))

        assertEquals(setOf("token-a", "token-b"), ids.toSet())
    }

    @Test
    fun `a secret put without a capabilityId is never returned by listSecretIds`() {
        val vault = InMemorySecretsVault()
        vault.putSecret("token-unscoped", "u")

        assertEquals(emptyList(), vault.listSecretIds(CapabilityId("termux.package_manager")))
    }

    @Test
    fun `getSecret logs secret use naming the id and capability, never the value`() {
        val auditLog = RecordingAuditLog()
        val vault = InMemorySecretsVault(auditLog)
        vault.putSecret("termux-api-token", "s3cr3t-value", capabilityId = CapabilityId("termux.package_manager"))

        vault.getSecret("termux-api-token")

        val event = auditLog.events.single { it.type == AuditEventType.SECRET_ACCESSED }
        assertEquals("secret:termux-api-token", event.subject)
        assertTrue(event.detail.contains("termux.package_manager"))
        assertTrue(event.detail.contains("success"))
        assertTrue(!event.detail.contains("s3cr3t-value"))
    }

    @Test
    fun `getSecret on an unknown id still logs the attempt as not found`() {
        val auditLog = RecordingAuditLog()
        val vault = InMemorySecretsVault(auditLog)

        vault.getSecret("missing")

        val event = auditLog.events.single { it.type == AuditEventType.SECRET_ACCESSED }
        assertTrue(event.detail.contains("not found"))
    }

    @Test
    fun `revokeSecret logs a SECRET_REVOKED event naming only the id`() {
        val auditLog = RecordingAuditLog()
        val vault = InMemorySecretsVault(auditLog)
        vault.putSecret("termux-api-token", "s3cr3t-value")

        vault.revokeSecret("termux-api-token")

        val event = auditLog.events.single { it.type == AuditEventType.SECRET_REVOKED }
        assertEquals("secret:termux-api-token", event.subject)
        assertTrue(!event.detail.contains("s3cr3t-value"))
    }

    @Test
    fun `with no audit log given, nothing is logged and behavior is unchanged`() {
        val vault = InMemorySecretsVault()
        vault.putSecret("k", "v")
        assertEquals("v", vault.getSecret("k"))
    }

    @Test
    fun `VaultBackedConfigSource delegates plain key lookups to the underlying ConfigSource`() {
        val delegate = MapConfigSource(mapOf("SOME_KEY" to "some-value"))
        val source = VaultBackedConfigSource(delegate, InMemorySecretsVault())

        assertEquals("some-value", source.get("SOME_KEY"))
        assertNull(source.get("OTHER_KEY"))
    }

    @Test
    fun `VaultBackedConfigSource exposes the configured vault`() {
        val vault = InMemorySecretsVault()
        vault.putSecret("k", "v")
        val source = VaultBackedConfigSource(MapConfigSource(emptyMap()), vault)

        assertEquals("v", source.getSecretsVault().getSecret("k"))
    }
}
