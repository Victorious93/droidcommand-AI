package ai.droidcommand.communications

import ai.droidcommand.communications.CommsPermission.ENABLE_AUTO_REPLY
import ai.droidcommand.communications.CommsPermission.READ_CONTACTS
import ai.droidcommand.communications.CommsPermission.READ_IMPORTED
import ai.droidcommand.communications.CommsPermission.READ_MESSAGES
import ai.droidcommand.communications.CommsPermission.SEND_MESSAGES
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.InMemoryAuditLog
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrokerTest {
    private val to = mapOf("to" to "+1 555 010 0101", "body" to "running late")

    // ---- catalogue and discovery ----

    @Test fun theCatalogueHasExactlyTheRequestedCapabilitiesAndNoWayToChangeAuthority() {
        val ids = CommsCatalog.ALL.map { it.id }
        assertEquals(27, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("communications.") })
        listOf(
            "list_contacts", "search_contacts", "list_conversations", "read_conversation", "search_messages", "get_unread_messages", "generate_reply", "create_draft", "send_sms",
            "send_mms", "send_voice_message", "send_attachment", "send_email", "schedule_message", "place_call", "get_call_status", "get_call_history", "get_delivery_status",
            "import_conversation", "update_conversation", "get_style_profile", "update_style_profile", "summarize_conversation", "set_auto_reply", "pause_agent", "cancel_job", "list_providers",
        )
            .forEach { assertTrue("communications.$it" in ids, it) }
        assertTrue("no capability can create or widen a grant", ids.none { it.contains("grant") || it.contains("permission") })
    }

    @Test fun discoveryTellsTheTruthAboutWhatIsAvailable() {
        val r = Rig()
        r.backend.unavailable["communications.send_sms"] = "VictorSMS is not the default SMS app"
        val caps = r.discover().caps().associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("unavailable", caps.getValue("communications.send_sms")["status"]!!.jsonPrimitive.content)
        assertEquals("VictorSMS is not the default SMS app", caps.getValue("communications.send_sms")["reason"]!!.jsonPrimitive.content)
        assertEquals("available", caps.getValue("communications.list_contacts")["status"]!!.jsonPrimitive.content)
        assertEquals("per_call", caps.getValue("communications.send_sms")["approval"]!!.jsonPrimitive.content)
        assertEquals(27, caps.size)
        val noAi = Rig(withModel = false).discover().caps().first { it["id"]!!.jsonPrimitive.content == "communications.generate_reply" }
        assertEquals("unavailable", noAi["status"]!!.jsonPrimitive.content)
    }

    @Test fun aCompositeBackendPassesAnAvailableCapabilityThroughAndOnlyReportsAMissingAppAsNotConnected() {
        val sms = FakeBackend()
        val composite = CompositeBackend(mapOf(Owner.VICTORSMS to sms))
        assertEquals(null, composite.availability("communications.list_contacts"), "available means null, not 'not connected'")
        sms.unavailable["communications.list_contacts"] = "contacts permission missing"
        assertEquals("contacts permission missing", composite.availability("communications.list_contacts"))
        assertTrue(composite.availability("communications.get_style_profile")!!.contains("not connected"), "VictorBots is not part of this composite")
        assertTrue(composite.call("communications.get_style_profile", mapOf("profile_id" to "p"), BackendContext("a", null, "r", "none", null)) is BackendResult.Refused)
    }

    @Test fun incompatibleVersionsAreRefusedGracefullyAndNewerClientsAreNegotiatedDown() {
        val r = Rig()
        val old = r.discover(v = 2, minV = 2)
        assertEquals("incompatible_version", old.code)
        assertEquals(1, old["versions"]!!.jsonObject["max"]!!.jsonPrimitive.content.toInt())
        val newer = r.discover(v = 5, minV = 1)
        assertTrue(newer.okFlag)
        assertEquals("1", newer["v"]!!.jsonPrimitive.content)
    }

    @Test fun malformedOversizedAndAnonymousRequestsAreRefused() {
        val r = Rig()
        assertEquals("invalid_request", r.parse(r.broker.handle(Rig.APP, "not json")).code)
        assertEquals("invalid_request", r.parse(r.broker.handle(Rig.APP, "x".repeat(70_000))).code)
        assertEquals("invalid_request", r.parse(r.broker.handle(Rig.APP, null)).code)
        assertEquals("not_paired", r.parse(r.broker.handle("", """{"v":1,"task":"discover","request_id":"abcdefgh"}""")).code)
        assertEquals("invalid_request", r.parse(r.broker.handle(Rig.APP, """{"v":1,"task":"call","request_id":"bad id!"}""")).code)
        assertEquals("unknown_capability", r.call("communications.grant_everything").code)
    }

    // ---- authorisation ----

    @Test fun withoutAGrantNothingRunsAndTheCallerIsToldWhatIsMissing() {
        val r = Rig()
        val res = r.call("communications.list_contacts")
        assertEquals("not_granted", res.code)
        assertEquals("read_contacts", res["missing"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(r.backend.calls.isEmpty())
    }

    @Test fun aGrantAppliesOnlyToItsAppBotContactAndLifetime() {
        val r = Rig()
        r.grant(Rig.APP, READ_CONTACTS, bot = "bot-a")
        assertEquals("completed", r.call("communications.list_contacts", bot = "bot-a").state)
        assertEquals("not_granted", r.call("communications.list_contacts", bot = "bot-b").code)
        assertEquals("not_granted", r.call("communications.list_contacts", caller = Rig.OTHER, bot = "bot-a").code)
        val scoped = Rig()
        scoped.grant(Rig.APP, READ_MESSAGES, READ_IMPORTED, contacts = setOf("c:7"))
        assertEquals("completed", scoped.call("communications.read_conversation", mapOf("conversation" to "7")).state)
        assertEquals("not_granted", scoped.call("communications.read_conversation", mapOf("conversation" to "8")).code)
        val expiring = Rig()
        expiring.grant(Rig.APP, READ_CONTACTS, expires = expiring.now + 1000)
        assertEquals("completed", expiring.call("communications.list_contacts").state)
        expiring.now += 2000
        assertEquals("not_granted", expiring.call("communications.list_contacts").code)
        val revoked = Rig()
        val g = revoked.grant(Rig.APP, READ_CONTACTS)
        revoked.grants.revoke(g.id)
        assertEquals("not_granted", revoked.call("communications.list_contacts").code)
    }

    @Test fun aOneTimeGrantWorksOnceThenIsSpent() {
        val r = Rig()
        r.grant(Rig.APP, READ_CONTACTS, oneTime = true)
        assertEquals("completed", r.call("communications.list_contacts").state)
        assertEquals("not_granted", r.call("communications.list_contacts").code)
    }

    @Test fun argumentsCannotSmuggleInPermissionsOrUnknownFields() {
        val r = Rig()
        r.grantAll()
        assertEquals("invalid_args", r.call("communications.list_contacts", mapOf("permissions" to "all")).code)
        assertEquals("invalid_args", r.call("communications.send_sms", mapOf("body" to "x")).code)
        assertEquals("invalid_args", r.call("communications.send_sms", mapOf("to" to "5550100101", "conversation" to "1", "body" to "x")).code)
        assertEquals("invalid_args", r.call("communications.read_conversation", mapOf("conversation" to "1", "direction" to "sideways")).code)
        assertTrue(r.backend.calls.isEmpty())
    }

    // ---- approval, idempotency, replay ----

    @Test fun aSendWaitsForTheOwnerThenRunsExactlyOnceNoMatterHowOftenItIsResent() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES)
        val first = r.call("communications.send_sms", to, rid = "send-0001")
        assertEquals("pending_approval", first.state)
        assertTrue("nothing reached VictorSMS yet", r.backend.calls.isEmpty())
        val approvalId = first["approval_id"]!!.jsonPrimitive.content
        assertEquals(1, r.approvals.pending().size)
        eqm("still pending", "pending_approval", r.call("communications.send_sms", to, rid = "send-0001").state)
        assertTrue(r.approvals.decide(approvalId, approve = true))
        val done = r.call("communications.send_sms", to, rid = "send-0001")
        assertEquals("completed", done.state)
        assertEquals(1, r.backend.calls.size)
        assertEquals("approved", r.backend.calls[0].third.autonomy)
        assertEquals(approvalId, r.backend.calls[0].third.approvalId)
        repeat(3) { assertEquals("completed", r.call("communications.send_sms", to, rid = "send-0001").state) }
        assertEquals("a replayed request never sends twice", 1, r.backend.calls.size)
    }

    @Test fun anApprovalIsBoundToTheExactRequestAndCannotAuthoriseDifferentText() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES)
        val first = r.call("communications.send_sms", to, rid = "send-0002")
        r.approvals.decide(first["approval_id"]!!.jsonPrimitive.content, true)
        val swapped = r.call("communications.send_sms", to + ("body" to "send me your password"), rid = "send-0002")
        assertEquals("request_id_reused", swapped.code)
        assertTrue(r.backend.calls.isEmpty())
        val other = r.call("communications.send_sms", to + ("body" to "send me your password"), rid = "send-0003")
        eqm("a new request needs its own approval", "pending_approval", other.state)
    }

    @Test fun denialAndExpiryAreFinalAndNothingRuns() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES)
        val a = r.call("communications.send_sms", to, rid = "send-0004")
        r.approvals.decide(a["approval_id"]!!.jsonPrimitive.content, false)
        assertEquals("denied", r.call("communications.send_sms", to, rid = "send-0004").code)
        val b = r.call("communications.send_sms", to, rid = "send-0005", expires = r.now + 30_000)
        r.approvals.decide(b["approval_id"]!!.jsonPrimitive.content, true)
        r.now += 31_000
        val late = r.call("communications.send_sms", to, rid = "send-0005", expires = r.now + 30_000)
        assertTrue("an expired approval cannot be revived: $late", late.code == "approval_expired" || late.code == "request_id_reused")
        assertTrue(r.backend.calls.isEmpty())
    }

    @Test fun anAutoClaimIsIgnoredUnlessTheOwnerGrantedAutoReplyForThatBot() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES)
        assertEquals("pending_approval", r.call("communications.send_sms", to, bot = "bot-1", mode = "auto").state)
        assertTrue(r.backend.calls.isEmpty())
        val auto = Rig()
        auto.grant(Rig.APP, SEND_MESSAGES, ENABLE_AUTO_REPLY, bot = "bot-1")
        assertEquals("completed", auto.call("communications.send_sms", to, bot = "bot-1", mode = "auto").state)
        assertEquals("auto", auto.backend.calls[0].third.autonomy)
        eqm("the grant is per bot: another bot has no authority at all", "not_granted", auto.call("communications.send_sms", to, bot = "bot-2", mode = "auto").code)
    }

    @Test fun replayedNoncesStaleTimestampsAndExpiredRequestsAreRefused() {
        val r = Rig()
        r.grantAll()
        val n = r.nonce()
        assertEquals("completed", r.call("communications.list_contacts", nonce = n).state)
        assertEquals("replayed_nonce", r.call("communications.list_contacts", nonce = n).code)
        assertEquals("stale_timestamp", r.call("communications.list_contacts", ts = r.now - 10 * 60_000).code)
        assertEquals("expired", r.call("communications.list_contacts", expires = r.now - 1).code)
        assertEquals("invalid_request", r.call("communications.list_contacts", expires = r.now + 3_600_000).code)
    }

    @Test fun receiptsBelongToTheirCallerAndNeverLeakToAnother() {
        val r = Rig()
        r.grantAll()
        r.grantAll(Rig.OTHER)
        r.call("communications.list_contacts", rid = "rcpt-0001")
        assertEquals("completed", r.receipt("rcpt-0001").state)
        assertEquals(false, r.receipt("rcpt-0001", caller = Rig.OTHER)["known"]!!.jsonPrimitive.content.toBoolean())
        eqm("the same id is a different request for another app", "completed", r.call("communications.list_contacts", rid = "rcpt-0001", caller = Rig.OTHER).state)
    }

    // ---- limits, emergency stop, uncertainty ----

    @Test fun aFloodOfSendsIsRateLimited() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES)
        val codes = (1..12).map { r.call("communications.send_sms", to, rid = "flood-%04d".format(it)) }.map { it.state ?: it.code }
        assertEquals(10, codes.count { it == "pending_approval" })
        assertEquals(2, codes.count { it == "refused" })
    }

    @Test fun theEmergencyStopBlocksSendsCallsAndEnablingButNeverReadingPausingOrDisabling() {
        val r = Rig()
        r.grantAll()
        r.kill.engage()
        assertEquals("kill_switch", r.call("communications.send_sms", to).code)
        assertEquals("kill_switch", r.call("communications.place_call", mapOf("to" to "5550100101")).code)
        assertEquals("kill_switch", r.call("communications.set_auto_reply", mapOf("conversation" to "1", "bot_id" to "b", "enabled" to "true")).code)
        assertEquals("completed", r.call("communications.list_contacts").state)
        assertEquals("completed", r.call("communications.pause_agent").state)
        assertEquals("completed", r.call("communications.set_auto_reply", mapOf("conversation" to "1", "bot_id" to "b", "enabled" to "false")).state)
        assertTrue(r.discover()["kill_switch"]!!.jsonPrimitive.content.toBoolean())
        r.kill.release()
        assertEquals("pending_approval", r.call("communications.send_sms", to).state)
    }

    @Test fun enablingAutoReplyAlwaysNeedsTheOwnerButDisablingNeverDoes() {
        val r = Rig()
        r.grant(Rig.APP, ENABLE_AUTO_REPLY)
        val enable = r.call("communications.set_auto_reply", mapOf("conversation" to "1", "bot_id" to "b", "enabled" to "true", "mode" to "auto"))
        assertEquals("pending_approval", enable.state)
        assertEquals("completed", r.call("communications.set_auto_reply", mapOf("conversation" to "1", "bot_id" to "b", "enabled" to "false")).state)
    }

    @Test fun anUncertainSendIsReportedAsUnknownAndNeverRetried() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES)
        r.backend.result = { BackendResult.Uncertain("VictorSMS stopped answering after taking the message") }
        val p = r.call("communications.send_sms", to, rid = "unsure-01")
        r.approvals.decide(p["approval_id"]!!.jsonPrimitive.content, true)
        val res = r.call("communications.send_sms", to, rid = "unsure-01")
        assertEquals("unknown", res.state)
        assertFalse(res.okFlag)
        r.backend.result = { BackendResult.Ok() }
        eqm("a resend returns the stored outcome", "unknown", r.call("communications.send_sms", to, rid = "unsure-01").state)
        assertEquals(1, r.backend.calls.size)
    }

    @Test fun aCrashDuringASendIsUnknownButDuringAReadIsAFailure() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES, READ_CONTACTS)
        r.backend.throwOn += listOf("communications.send_sms", "communications.list_contacts")
        val p = r.call("communications.send_sms", to, rid = "crash-001")
        r.approvals.decide(p["approval_id"]!!.jsonPrimitive.content, true)
        assertEquals("unknown", r.call("communications.send_sms", to, rid = "crash-001").state)
        assertEquals("failed", r.call("communications.list_contacts").state)
    }

    @Test fun jobsCanOnlyBeCancelledByTheAppThatStartedThem() {
        val r = Rig()
        r.grantAll()
        r.grantAll(Rig.OTHER)
        r.backend.result = { cap -> if (cap == "communications.schedule_message") BackendResult.Running("job-42") else BackendResult.Ok() }
        val s = r.call("communications.schedule_message", mapOf("to" to "5550100101", "body" to "hi", "send_at" to "1900000000000"), rid = "sched-001")
        r.approvals.decide(s["approval_id"]!!.jsonPrimitive.content, true)
        val started = r.call("communications.schedule_message", mapOf("to" to "5550100101", "body" to "hi", "send_at" to "1900000000000"), rid = "sched-001")
        assertEquals("running", started.state)
        assertEquals("not_your_job", r.call("communications.cancel_job", mapOf("job_id" to "job-42"), caller = Rig.OTHER).code)
        assertEquals("completed", r.call("communications.cancel_job", mapOf("job_id" to "job-42")).state)
    }

    // ---- audit ----

    @Test fun decisionsAreAuditedAndAFullAuditLogStopsSendsFailingClosed() {
        val r = Rig()
        r.grant(Rig.APP, SEND_MESSAGES)
        r.call("communications.list_contacts")
        val p = r.call("communications.send_sms", to, rid = "audit-001")
        r.approvals.decide(p["approval_id"]!!.jsonPrimitive.content, true)
        r.call("communications.send_sms", to, rid = "audit-001")
        val types = r.audit.all().map { it.type }
        assertTrue(AuditEventType.ACCESS_DENIED in types)
        assertTrue(AuditEventType.APPROVAL_APPROVED in types)
        assertTrue(r.audit.all().all { it.subject.startsWith("companion_comms:") })

        val full = Rig()
        val tiny = CommsBroker(full.backend, null, full.grants, full.approvals, InMemoryReceiptStore(), full.kill, InMemoryAuditLog(capacity = 0), { full.now })
        full.grant(Rig.APP, SEND_MESSAGES)
        val pend = full.parse(tiny.handle(Rig.APP, full.request("communications.send_sms", to, "audit-002")))
        full.approvals.decide(pend["approval_id"]!!.jsonPrimitive.content, true)
        val res = full.parse(tiny.handle(Rig.APP, full.request("communications.send_sms", to, "audit-002")))
        assertEquals("audit_unavailable", res.code)
        assertTrue(full.backend.calls.isEmpty())
    }
}
