package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuditLogTest {
    @Test
    fun `records events up to capacity`() {
        val log = InMemoryAuditLog(capacity = 2)
        assertTrue(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "a", "first")))
        assertTrue(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "b", "second")))
        assertEquals(2, log.all().size)
    }

    @Test
    fun `fails closed once at capacity, rather than evicting the oldest entry`() {
        val log = InMemoryAuditLog(capacity = 1)
        assertTrue(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "a", "kept")))
        assertFalse(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "b", "rejected")))
        assertEquals(listOf("kept"), log.all().map { it.detail })
    }
}
