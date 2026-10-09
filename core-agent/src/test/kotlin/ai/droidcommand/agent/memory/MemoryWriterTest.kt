package ai.droidcommand.agent.memory

import ai.droidcommand.agent.InMemoryKnowledgeGraph
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemoryWriterTest {
    private val graph = InMemoryKnowledgeGraph()
    private val events = mutableListOf<String>()
    private var n = 0
    private val writer = MemoryWriter(
        graph,
        audit = MemoryAuditSink { events += it },
        clock = { Instant.parse("2026-10-09T00:00:00Z").plusSeconds(n.toLong()) },
        idGenerator = { "id${++n}" },
    )

    private fun cand(
        content: String,
        title: String = "t",
        origin: Origin = Origin.USER,
        subject: String? = null,
        requested: Verification? = null,
        explicit: Boolean = false,
        scope: String = "project:dca",
    ) = MemoryCandidate(title, content, MemoryClass.PROJECT, scope, origin, requested, subject = subject, explicitUserSave = explicit)

    @Test
    fun `credential-like content is rejected and never persisted or audited verbatim`() {
        val secret = "sk-ant-api03-ABCDEFGHIJKLMNOPQRSTUV"
        val d = writer.write(cand("my key is $secret"))
        assertIs<MemoryDecision.Rejected>(d)
        assertTrue(graph.searchEntities("").isEmpty())
        assertTrue(events.none { secret in it }, "audit must not echo the secret")
        assertFalse(d.reason.contains(secret))
    }

    @Test
    fun `password assignments and private keys are secrets`() {
        assertIs<MemoryDecision.Rejected>(writer.write(cand("password = hunter2hunter2")))
        assertIs<MemoryDecision.Rejected>(writer.write(cand("-----BEGIN RSA PRIVATE KEY-----\nabc")))
        assertTrue(graph.searchEntities("").isEmpty())
    }

    @Test
    fun `personal data needs an explicit user save`() {
        assertIs<MemoryDecision.Rejected>(writer.write(cand("contact me at jane@example.com")))
        val ok = writer.write(cand("contact me at jane@example.com", explicit = true))
        val stored = assertIs<MemoryDecision.Stored>(ok)
        assertEquals(Sensitivity.PERSONAL, MemoryMetadata.from(stored.entity)!!.sensitivity)
    }

    @Test
    fun `a model inference can never be stored as verified`() {
        val d = assertIs<MemoryDecision.Stored>(writer.write(cand("module X is unused", origin = Origin.MODEL, requested = Verification.VERIFIED)))
        assertEquals(Verification.MODEL_INFERRED, MemoryMetadata.from(d.entity)!!.verification)
        assertTrue(d.notes.any { "lowered" in it })
    }

    @Test
    fun `only tool observations can be verified and a user cannot self-verify`() {
        assertEquals(Verification.VERIFIED, MemoryWriter.effectiveVerification(Origin.TOOL, null))
        assertEquals(Verification.USER_ASSERTED, MemoryWriter.effectiveVerification(Origin.USER, Verification.VERIFIED))
        assertEquals(Verification.HYPOTHESIS, MemoryWriter.effectiveVerification(Origin.IMPORT, Verification.USER_ASSERTED))
        assertEquals(Verification.HYPOTHESIS, MemoryWriter.effectiveVerification(Origin.MODEL, Verification.HYPOTHESIS))
        assertEquals(Verification.MODEL_INFERRED, MemoryWriter.effectiveVerification(Origin.MODEL, Verification.SUPERSEDED))
    }

    @Test
    fun `duplicates are detected and a more trusted source reinforces the existing record`() {
        val first = assertIs<MemoryDecision.Stored>(writer.write(cand("The build uses Gradle with Kotlin 2.4", origin = Origin.MODEL)))
        val dup = assertIs<MemoryDecision.Duplicate>(writer.write(cand("the build uses gradle with kotlin 2.4!", origin = Origin.TOOL)))
        assertEquals(first.entity.id, dup.existingId)
        assertTrue(dup.reinforced)
        assertEquals(1, MemoryRecords.all(graph).size)
        assertEquals(Verification.VERIFIED, MemoryRecords.all(graph).single().meta.verification)
    }

    @Test
    fun `a lower trust duplicate does not downgrade the existing record`() {
        writer.write(cand("Tests run with gradlew test", origin = Origin.TOOL))
        val dup = assertIs<MemoryDecision.Duplicate>(writer.write(cand("Tests run with gradlew test", origin = Origin.MODEL)))
        assertFalse(dup.reinforced)
        assertEquals(Verification.VERIFIED, MemoryRecords.all(graph).single().meta.verification)
    }

    @Test
    fun `same content in a different scope is not a duplicate`() {
        writer.write(cand("Uses Room", scope = "project:a"))
        assertIs<MemoryDecision.Stored>(writer.write(cand("Uses Room", scope = "project:b")))
    }

    @Test
    fun `a newer equally trusted fact supersedes the old one and keeps it as history`() {
        val old = assertIs<MemoryDecision.Stored>(writer.write(cand("Target branch is main", subject = "target-branch")))
        val new = assertIs<MemoryDecision.Stored>(writer.write(cand("Target branch is develop", subject = "target-branch")))
        assertEquals(old.entity.id, new.supersededId)
        val oldNow = MemoryAdmin(graph).inspect(old.entity.id)!!
        assertEquals(Verification.SUPERSEDED, oldNow.meta.verification)
        assertEquals("Target branch is main", oldNow.meta.content, "original text must be preserved")
        val edge = graph.relationshipsFrom(new.entity.id).single()
        assertEquals(MemoryRelations.FACT_SUPERSEDES, edge.type)
        assertEquals(old.entity.id, edge.toId)
    }

    @Test
    fun `a less trusted claim against a verified fact is a conflict and writes nothing`() {
        val fact = assertIs<MemoryDecision.Stored>(writer.write(cand("JDK is 21", origin = Origin.TOOL, subject = "jdk")))
        val d = writer.write(cand("JDK is 17", origin = Origin.MODEL, subject = "jdk"))
        assertEquals(fact.entity.id, assertIs<MemoryDecision.Conflict>(d).existingId)
        assertEquals(1, MemoryRecords.all(graph).size)
        assertEquals(Verification.VERIFIED, MemoryRecords.all(graph).single().meta.verification)
    }

    @Test
    fun `an explicit user correction wins over a verified fact`() {
        writer.write(cand("Reply language is French", origin = Origin.TOOL, subject = "lang"))
        val d = assertIs<MemoryDecision.Stored>(writer.write(cand("Reply language is English", subject = "lang", explicit = true)))
        assertNotNull(d.supersededId)
    }

    @Test
    fun `invalid input is rejected`() {
        assertIs<MemoryDecision.Rejected>(writer.write(cand("  ")))
        assertIs<MemoryDecision.Rejected>(writer.write(cand("x".repeat(MemoryWriter.MAX_CONTENT_CHARS + 1))))
        assertIs<MemoryDecision.Rejected>(writer.write(cand("ok", scope = " ")))
        assertIs<MemoryDecision.Rejected>(writer.write(cand("ok").copy(retentionDays = 0)))
    }

    @Test
    fun `metadata round-trips through entity properties and malformed entities are ignored`() {
        val meta = MemoryMetadata(
            MemoryClass.DEVICE, "Pixel 8, Android 15", "device:p8", Origin.TOOL, Verification.VERIFIED,
            confidence = 0.9, pinned = true, subject = "model", summary = "Pixel", sourceRef = "adb", tags = setOf("b", "a"), retentionDays = 30,
        )
        val e = ai.droidcommand.agent.Entity("x", MemoryClass.DEVICE.entityType, "Pixel", meta.toProperties())
        assertEquals(meta, MemoryMetadata.from(e))
        assertEquals(null, MemoryMetadata.from(e.copy(properties = e.properties + (MemoryMetadata.K_CLASS to "BOGUS"))))
        assertEquals(null, MemoryMetadata.from(ai.droidcommand.agent.Entity("y", ai.droidcommand.agent.EntityType.NOTE, "plain")))
    }
}
