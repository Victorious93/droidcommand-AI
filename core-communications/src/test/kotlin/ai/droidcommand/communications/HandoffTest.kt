package ai.droidcommand.communications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bots text and respond by CONVERSATION, as a draft the owner sends. No number is accepted and nothing here sends. */
class HandoffTest {
    private val archive = FakeArchive()
    private val handoff = FakeArchive().apply { answer = { _, _ -> ok("draftId" to "x", "state" to "received") } }
    private val backend = VictorSmsCommsBackend(archive, { 1_000L }, handoff = handoff)
    private val uid = "0123456789abcdef0123456789abcdef"
    private fun ctx(id: String = "req-000001") = BackendContext("com.victorious.bots", "bot1", id, "approved", null)
    private fun ok(vararg p: Pair<String, String>) = ArchiveAnswer.Ok(buildJsonObject { p.forEach { (k, v) -> put(k, v) } })
    private fun json(r: BackendResult) = Json.parseToJsonElement((r as BackendResult.Ok).json).jsonObject

    @Test fun `a draft goes to VictorSMS by conversation with exactly three fields and never a phone number`() {
        val r = backend.call("communications.create_draft", mapOf("conversation" to uid, "body" to "On my way", "bot_id" to "bot1"), ctx())
        val (op, params) = handoff.calls.single()
        assertEquals(HandoffWire.FROM_ARCHIVE, op)
        assertEquals(setOf("draftId", "conversation", "body"), params.keys, "bot_id and anything else stay behind")
        assertEquals(uid, params["conversation"]!!.jsonPrimitive.content)
        val j = json(r)
        assertEquals("false", j["sent"]!!.jsonPrimitive.content)
        assertEquals("awaiting_owner", j["state"]!!.jsonPrimitive.content)
        assertEquals("true", j["awaiting_owner"]!!.jsonPrimitive.content)
    }

    @Test fun `texting by conversation is the same draft, marked queued for the owner, and still not sent`() {
        val r = json(backend.call("communications.send_sms", mapOf("conversation" to uid, "body" to "Running late"), ctx()))
        assertEquals("true", r["queued_for_owner"]!!.jsonPrimitive.content)
        assertEquals("false", r["sent"]!!.jsonPrimitive.content)
        assertEquals(HandoffWire.FROM_ARCHIVE, handoff.calls.single().first)
    }

    @Test fun `a phone number is refused and never reaches VictorSMS`() {
        val r = backend.call("communications.send_sms", mapOf("to" to "+15551230001", "body" to "hi"), ctx())
        assertTrue(r is BackendResult.Refused && r.code == "send_by_conversation")
        // A number WITH a conversation must be refused too: quietly ignoring it would draft to the conversation's contact, not the person named.
        val both = backend.call("communications.send_sms", mapOf("to" to "+15551230001", "conversation" to uid, "body" to "hi"), ctx())
        assertTrue(both is BackendResult.Refused && both.code == "send_by_conversation")
        val blank = backend.call("communications.send_sms", mapOf("conversation" to " ", "body" to "hi"), ctx())
        assertTrue(blank is BackendResult.Refused && blank.code == "send_by_conversation")
        assertTrue(handoff.calls.isEmpty())
    }

    @Test fun `the same request is the same draft, a different request is a different one`() {
        fun id(req: String): String {
            handoff.calls.clear()
            backend.call("communications.create_draft", mapOf("conversation" to uid, "body" to "hi"), ctx(req))
            return handoff.calls.single().second["draftId"]!!.jsonPrimitive.content
        }
        assertEquals(id("req-000001"), id("req-000001"), "a retry cannot become a second draft")
        assertNotEquals(id("req-000001"), id("req-000002"))
        assertTrue(Regex("^[A-Za-z0-9._:-]{1,96}$").matches(id("req with spaces/and*odd?chars")), "always a draft id VictorSMS accepts")
        assertTrue(Regex("^[A-Za-z0-9._:-]{1,96}$").matches(id("")))
        assertTrue(Regex("^[A-Za-z0-9._:-]{1,96}$").matches(id("x".repeat(500))))
    }

    @Test fun `only the two draft ops can ever be called, never a send`() {
        backend.call("communications.create_draft", mapOf("conversation" to uid, "body" to "a"), ctx("r1"))
        backend.call("communications.send_sms", mapOf("conversation" to uid, "body" to "b"), ctx("r2"))
        backend.call("communications.get_delivery_status", mapOf("message_id" to "dca-r1"), ctx("r3"))
        assertTrue(handoff.calls.map { it.first }.all { it in HandoffWire.OPS }, handoff.calls.map { it.first }.toString())
        assertTrue(handoff.calls.none { it.first.startsWith("sms.") })
    }

    @Test fun `VictorSMS refusals are explained in plain words`() {
        fun refuse(code: String): BackendResult.Refused {
            handoff.answer = { _, _ -> ArchiveAnswer.Refused(code, "raw") }
            return backend.call("communications.create_draft", mapOf("conversation" to uid, "body" to "hi"), ctx()) as BackendResult.Refused
        }
        assertTrue(refuse("permission_denied").message.contains("Drafts"))
        assertTrue(refuse("no_sms_recipient").message.contains("one-to-one"))
        assertTrue(refuse("not_found").message.contains("shared with the assistant"))
        assertTrue(refuse("not_implemented").message.contains("Update VictorSMS"))
        assertEquals("no_sms_recipient", refuse("no_sms_recipient").code)
        handoff.answer = { _, _ -> ArchiveAnswer.Unavailable("VictorSMS is not installed") }
        val r = backend.call("communications.create_draft", mapOf("conversation" to uid, "body" to "hi"), ctx())
        assertTrue(r is BackendResult.Refused && r.code == "backend_unavailable")
    }

    @Test fun `delivery status maps each VictorSMS state and never claims delivery`() {
        val expect = mapOf("received" to "awaiting_owner", "submitted" to "submitted", "declined" to "declined", "failed" to "failed", "unknown" to "unknown", "weird" to "unknown")
        for ((raw, plain) in expect) {
            handoff.answer = { _, _ -> ok("draftId" to "dca-r1", "state" to raw) }
            val j = json(backend.call("communications.get_delivery_status", mapOf("message_id" to "dca-r1"), ctx()))
            assertEquals(plain, j["state"]!!.jsonPrimitive.content, raw)
            assertEquals("false", j["delivery_confirmed"]!!.jsonPrimitive.content, "submitted is not delivered")
        }
    }

    @Test fun `a malformed draft id is refused before anything is sent to VictorSMS`() {
        handoff.calls.clear()
        for (bad in listOf("", "has space", "semi;colon", "x".repeat(97), "../x")) {
            val r = backend.call("communications.get_delivery_status", mapOf("message_id" to bad), ctx())
            assertTrue(r is BackendResult.Refused && r.code == "invalid_parameters", bad)
        }
        assertTrue(handoff.calls.isEmpty())
    }

    @Test fun `without the hand-off wired these capabilities stay unavailable and reads are unaffected`() {
        val plain = VictorSmsCommsBackend(archive, { 1_000L })
        VictorSmsCommsBackend.HANDOFF_CAPABILITIES.forEach {
            assertEquals(VictorSmsCommsBackend.NOT_PROVIDED, plain.availability(it), it)
            assertTrue(plain.call(it, mapOf("conversation" to uid, "body" to "x"), ctx()) is BackendResult.Refused)
        }
        assertNull(backend.availability("communications.read_conversation"))
        backend.call("communications.read_conversation", mapOf("conversation" to uid), ctx())
        assertTrue(archive.calls.any { it.first == ArchiveWire.READ }, "reads still use the archive channel")
        assertFalse(handoff.calls.any { it.first == ArchiveWire.READ })
    }

    @Test fun `availability follows the archive pairing when the hand-off is wired`() {
        VictorSmsCommsBackend.HANDOFF_CAPABILITIES.forEach { assertNull(backend.availability(it), it) }
        val down = FakeArchive().apply { caps = ArchiveAnswer.Refused("permission_denied", "x") }
        val b = VictorSmsCommsBackend(down, { 1_000L }, handoff = handoff)
        VictorSmsCommsBackend.HANDOFF_CAPABILITIES.forEach { assertTrue(b.availability(it) != null, it) }
    }
}
