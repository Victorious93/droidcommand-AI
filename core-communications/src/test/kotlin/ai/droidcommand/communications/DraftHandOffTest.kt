package ai.droidcommand.communications

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Victorious Framework: "Send via VictorSMS" hands a chosen reply over as a draft; the owner presses Send there. */
class DraftHandOffTest {
    private val inbox = DraftInbox()
    private val ch = FakeArchive().apply { answer = { _, _ -> ArchiveAnswer.Ok(buildJsonObject { put("state", "received") }) } }
    private val handOff = DraftHandOff(inbox, ch)
    private val conv = "0123456789abcdef0123456789abcdef"
    private fun pending() = inbox.add(conv, "m1", "Sounds good, see you at 6", null)
    private fun reply(state: String) {
        ch.answer = { _, _ -> ArchiveAnswer.Ok(buildJsonObject { put("state", state) }) }
    }

    @Test fun `hand-off sends conversation and text only, never a number, and marks the draft handed off`() {
        val d = pending()
        val out = handOff.handOff(d.id)
        assertTrue(out is DraftHandOff.Outcome.Done)
        val (op, params) = ch.calls.single()
        assertEquals(HandoffWire.FROM_ARCHIVE, op)
        assertEquals(setOf("draftId", "conversation", "body"), params.keys)
        assertEquals(conv, params["conversation"]!!.jsonPrimitive.content)
        assertEquals("Sounds good, see you at 6", params["body"]!!.jsonPrimitive.content)
        assertEquals(DraftHandOff.HANDED_OFF, inbox.drafts().single().state)
        assertTrue(inbox.pending().isEmpty(), "no longer offered as pending")
    }

    @Test fun `the owner's edited text is what is handed over`() {
        val d = pending()
        handOff.handOff(d.id, "  6:30 works better  ")
        assertEquals("6:30 works better", ch.calls.single().second["body"]!!.jsonPrimitive.content)
    }

    @Test fun `a second tap does not hand over twice`() {
        val d = pending()
        handOff.handOff(d.id)
        val again = handOff.handOff(d.id)
        assertTrue(again is DraftHandOff.Outcome.Refused)
        assertEquals(1, ch.calls.size)
    }

    @Test fun `empty or oversize text and unknown drafts never reach VictorSMS`() {
        val d = pending()
        assertTrue(handOff.handOff(d.id, "   ") is DraftHandOff.Outcome.Refused)
        assertTrue(handOff.handOff(d.id, "x".repeat(1_601)) is DraftHandOff.Outcome.Refused)
        assertTrue(handOff.handOff("nope") is DraftHandOff.Outcome.Refused)
        assertTrue(ch.calls.isEmpty())
        assertEquals("pending", inbox.drafts().single().state)
    }

    @Test fun `a refusal leaves the draft pending and explains why in plain words`() {
        val d = pending()
        ch.answer = { _, _ -> ArchiveAnswer.Refused("permission_denied", "x") }
        val out = handOff.handOff(d.id) as DraftHandOff.Outcome.Refused
        assertTrue("Drafts" in out.message && "Archive" in out.message)
        assertEquals("pending", inbox.drafts().single().state)
        ch.answer = { _, _ -> ArchiveAnswer.Refused("no_sms_recipient", "x") }
        assertTrue("one-to-one" in (handOff.handOff(d.id) as DraftHandOff.Outcome.Refused).message)
        ch.answer = { _, _ -> ArchiveAnswer.Unavailable("VictorSMS is not paired.") }
        assertEquals("VictorSMS is not paired.", (handOff.handOff(d.id) as DraftHandOff.Outcome.Refused).message)
        ch.answer = { _, _ -> ArchiveAnswer.Failed("timeout") }
        assertTrue(handOff.handOff(d.id) is DraftHandOff.Outcome.Refused)
        assertEquals("pending", inbox.drafts().single().state)
    }

    @Test fun `refresh follows what the owner did in VictorSMS and never claims delivery`() {
        val a = pending()
        val b = inbox.add(conv, "m2", "b", null)
        val c = inbox.add(conv, "m3", "c", null)
        val e = inbox.add(conv, "m4", "e", null)
        listOf(a, b, c, e).forEach { handOff.handOff(it.id) }
        ch.calls.clear()
        ch.answer = { _, p ->
            val state = when (p["draftId"]!!.jsonPrimitive.content) {
                DraftHandOff.wireId(a.id) -> "submitted"
                DraftHandOff.wireId(b.id) -> "declined"
                DraftHandOff.wireId(c.id) -> "failed"
                else -> "received"
            }
            ArchiveAnswer.Ok(buildJsonObject { put("state", state) })
        }
        assertEquals(3, handOff.refresh())
        val s = inbox.drafts().associate { it.id to it.state }
        assertEquals(DraftHandOff.SUBMITTED, s[a.id])
        assertEquals(DraftHandOff.DECLINED, s[b.id])
        assertEquals(DraftHandOff.HAND_OFF_FAILED, s[c.id])
        assertEquals(DraftHandOff.HANDED_OFF, s[e.id], "still waiting")
        assertEquals(0, handOff.refresh(), "nothing changes a second time")
        assertTrue(ch.calls.all { it.first == HandoffWire.STATUS })
    }

    @Test fun `an interrupted send is shown as unknown and an unreachable VictorSMS changes nothing`() {
        val d = pending()
        handOff.handOff(d.id)
        ch.answer = { _, _ -> ArchiveAnswer.Unavailable("offline") }
        assertEquals(0, handOff.refresh())
        assertEquals(DraftHandOff.HANDED_OFF, inbox.drafts().single().state)
        reply("unknown")
        assertEquals(1, handOff.refresh())
        assertEquals(DraftHandOff.UNKNOWN, inbox.drafts().single().state)
    }

    @Test fun `refresh only asks about drafts that were handed over`() {
        pending()
        handOff.refresh()
        assertFalse(ch.calls.any { it.first == HandoffWire.STATUS })
    }

    @Test fun `the emergency stop blocks both the hand-over and the status check`() {
        var stop = false
        val h = DraftHandOff(inbox, ch) { stop }
        val d = pending()
        stop = true
        assertTrue(h.handOff(d.id) is DraftHandOff.Outcome.Refused)
        assertEquals(0, h.refresh())
        assertTrue(ch.calls.isEmpty())
        stop = false
        assertTrue(h.handOff(d.id) is DraftHandOff.Outcome.Done)
        stop = true
        ch.calls.clear()
        h.refresh()
        assertTrue(ch.calls.isEmpty())
    }
}
