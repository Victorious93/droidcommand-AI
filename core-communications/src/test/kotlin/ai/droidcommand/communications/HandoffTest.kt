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

/**
 * Victorious Framework: the reply frames below are COPIES of what VictorSMS's `BridgeService.draftReply` builds (read from
 * `victorsms` `bridge/BridgeService.kt` and `DraftHandoff.kt`, 2026-11-26): success is `{draftId, state[, detail]}` with a lowercase
 * state; failures are the machine codes below. They are pinned here, not shared code, so a change on the VictorSMS side shows up as a
 * failing test only when someone updates the copy. Never run against a real VictorSMS.
 */
class HandoffFramesTest {
    private val handoff = FakeArchive()
    private val backend = VictorSmsCommsBackend(FakeArchive(), { 1_000L }, handoff = handoff)
    private val uid = "0123456789abcdef0123456789abcdef"
    private val ctx = BackendContext("com.victorious.bots", "bot1", "req-000002", "approved", null)
    private fun frame(vararg p: Pair<String, String>) = ArchiveAnswer.Ok(buildJsonObject { p.forEach { (k, v) -> put(k, v) } })
    private fun call(cap: String, args: Map<String, String>) = backend.call(cap, args, ctx)

    @Test fun `VictorSMS's success frame for a new draft is accepted as awaiting the owner`() {
        handoff.answer = { _, _ -> frame("draftId" to "dca-req-000002", "state" to "received") }
        val j = Json.parseToJsonElement((call("communications.create_draft", mapOf("conversation" to uid, "body" to "hi")) as BackendResult.Ok).json).jsonObject
        assertEquals("dca-req-000002", j["draft_id"]!!.jsonPrimitive.content)
        assertEquals("awaiting_owner", j["state"]!!.jsonPrimitive.content)
    }

    @Test fun `a repeated delivery of the same draft id returns the current state and is not treated as a new send`() {
        handoff.answer = { _, _ -> frame("draftId" to "dca-req-000002", "state" to "submitted") }
        val j = Json.parseToJsonElement((call("communications.send_sms", mapOf("conversation" to uid, "body" to "hi")) as BackendResult.Ok).json).jsonObject
        assertEquals("submitted", j["state"]!!.jsonPrimitive.content)
        assertEquals("false", j["awaiting_owner"]!!.jsonPrimitive.content)
        assertEquals("false", j["sent"]!!.jsonPrimitive.content, "this connector never claims it sent")
    }

    @Test fun `VictorSMS's status frame carries a detail that is shown as plain words, not echoed`() {
        handoff.answer = { _, _ -> frame("draftId" to "dca-req-000002", "state" to "unknown", "detail" to "Submission was interrupted. Check your conversation before sending again.") }
        val j = Json.parseToJsonElement((call("communications.get_delivery_status", mapOf("message_id" to "dca-req-000002")) as BackendResult.Ok).json).jsonObject
        assertEquals("unknown", j["state"]!!.jsonPrimitive.content)
        assertEquals("false", j["delivery_confirmed"]!!.jsonPrimitive.content)
        assertTrue("interrupted" in j["detail"]!!.jsonPrimitive.content)
    }

    @Test fun `every error code VictorSMS gives for these ops is explained in plain words`() {
        val expectations = mapOf(
            "not_found" to "shared with the assistant",
            "no_sms_recipient" to "one-to-one",
            "invalid_parameters" to "1,600",
            "permission_denied" to "Drafts",
            "expired" to "expired",
            "unsupported_operation" to "Update VictorSMS",
        )
        for ((code, words) in expectations) {
            handoff.answer = { _, _ -> ArchiveAnswer.Refused(code, "x") }
            val r = call("communications.create_draft", mapOf("conversation" to uid, "body" to "hi")) as BackendResult.Refused
            assertEquals(code, r.code)
            assertTrue(words in r.message, "$code -> ${r.message}")
        }
    }

    @Test fun `not_found on a status check means no record of the draft, not an unshared conversation`() {
        handoff.answer = { _, _ -> ArchiveAnswer.Refused("not_found", "x") }
        val r = call("communications.get_delivery_status", mapOf("message_id" to "dca-req-000002")) as BackendResult.Refused
        assertTrue("no record" in r.message && "shared" !in r.message, r.message)
    }

    @Test fun `a state this app does not know is reported unknown rather than guessed`() {
        handoff.answer = { _, _ -> frame("draftId" to "dca-req-000002", "state" to "delivered") }
        val j = Json.parseToJsonElement((call("communications.get_delivery_status", mapOf("message_id" to "dca-req-000002")) as BackendResult.Ok).json).jsonObject
        assertEquals("unknown", j["state"]!!.jsonPrimitive.content)
        assertEquals("false", j["delivery_confirmed"]!!.jsonPrimitive.content)
    }
}
