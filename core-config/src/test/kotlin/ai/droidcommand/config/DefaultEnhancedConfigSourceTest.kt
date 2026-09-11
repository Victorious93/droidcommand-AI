package ai.droidcommand.config

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private class NoopAuditLog : AuditLog {
    override fun record(event: AuditEvent): Boolean = true
}

class DefaultEnhancedConfigSourceTest {
    @Test
    fun `get delegates to the wrapped ConfigSource unchanged`() {
        val delegate = MapConfigSource(mapOf("KEY" to "value"))
        val source = DefaultEnhancedConfigSource(delegate, InMemorySecretsVault(NoopAuditLog()))

        assertEquals("value", source.get("KEY"))
        assertNull(source.get("MISSING"))
    }

    @Test
    fun `getSecretsVault returns the exact injected vault instance`() {
        val vault = InMemorySecretsVault(NoopAuditLog())
        val source = DefaultEnhancedConfigSource(MapConfigSource(emptyMap()), vault)

        assertSame(vault, source.getSecretsVault())
    }
}
