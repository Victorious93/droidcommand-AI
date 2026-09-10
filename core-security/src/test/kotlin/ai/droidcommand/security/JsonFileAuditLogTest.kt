package ai.droidcommand.security

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun newLog(capacity: Int = 10_000) = JsonFileAuditLog(Files.createTempDirectory("droidcommand-audit-log-test").resolve("audit.jsonl"), capacity)

class JsonFileAuditLogTest {
    @Test
    fun `records events up to capacity`() {
        val log = newLog(capacity = 2)
        assertTrue(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "a", "first")))
        assertTrue(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "b", "second")))
        assertEquals(2, log.all().size)
    }

    @Test
    fun `fails closed once at capacity, rather than evicting the oldest entry`() {
        val log = newLog(capacity = 1)
        assertTrue(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "a", "kept")))
        assertFalse(log.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "b", "rejected")))
        assertEquals(listOf("kept"), log.all().map { it.detail })
    }

    @Test
    fun `preserves event type, subject, detail, and timestamp through a round trip`() {
        val log = newLog()
        val timestamp = java.time.Instant.parse("2026-01-01T00:00:00Z")
        log.record(AuditEvent(AuditEventType.GRANT_REVOKED, "grant:g1", "revoked by admin", timestamp))

        val event = log.all().single()
        assertEquals(AuditEventType.GRANT_REVOKED, event.type)
        assertEquals("grant:g1", event.subject)
        assertEquals("revoked by admin", event.detail)
        assertEquals(timestamp, event.timestamp)
    }

    @Test
    fun `events survive a fresh log instance over the same file, oldest first`() {
        val file = Files.createTempDirectory("droidcommand-audit-log-test").resolve("audit.jsonl")
        JsonFileAuditLog(file).apply {
            record(AuditEvent(AuditEventType.ACCESS_GRANTED, "a", "first"))
            record(AuditEvent(AuditEventType.ACCESS_DENIED, "b", "second"))
        }

        val reopened = JsonFileAuditLog(file)
        assertEquals(listOf("first", "second"), reopened.all().map { it.detail })
    }

    @Test
    fun `capacity is enforced fresh against the file, not an in-process counter`() {
        val file = Files.createTempDirectory("droidcommand-audit-log-test").resolve("audit.jsonl")
        JsonFileAuditLog(file, capacity = 1).record(AuditEvent(AuditEventType.ACCESS_GRANTED, "a", "first"))

        val second = JsonFileAuditLog(file, capacity = 1)
        assertFalse(second.record(AuditEvent(AuditEventType.ACCESS_GRANTED, "b", "rejected")))
        assertEquals(1, second.all().size)
    }

    @Test
    fun `an empty log reports no events`() {
        assertEquals(emptyList(), newLog().all())
    }
}
