package ai.droidcommand.communications

import ai.droidcommand.communications.CommsPermission.READ_CONTACTS
import ai.droidcommand.communications.CommsPermission.SEND_MESSAGES
import ai.droidcommand.security.InMemoryAuditLog
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A broker whose state lives in files, "restarted" by building a second one over the same directory. */
class PersistenceTest {
    private val dir: Path = Files.createTempDirectory("comms-persist")
    private var now = 1_800_000_000_000L
    private var seq = 0
    private val backend = FakeBackend()
    private val to = mapOf("to" to "+1 555 010 0101", "body" to "running late")

    private inner class Instance {
        val grants = JsonFileGrantStore(dir.resolve("grants.json"))
        val approvals = ApprovalQueue({ now }, JsonFileApprovalStore(dir.resolve("approvals.json")))
        val kill = KillSwitch({ on -> if (on) Files.write(dir.resolve("kill"), byteArrayOf(1)) else Files.deleteIfExists(dir.resolve("kill")) }, Files.exists(dir.resolve("kill")))
        val receipts = JsonFileReceiptStore(dir.resolve("receipts.json"))
        val broker = CommsBroker(backend, null, grants, approvals, receipts, kill, InMemoryAuditLog(), { now })
        val admin = CommsAdmin(grants, approvals, kill, { now }) { "g" + (++seq) }
        fun call(cap: String, args: Map<String, String> = emptyMap(), rid: String, ts: Long = now) = CommsContract.JSON.parseToJsonElement(
            broker.handle(
                "com.victorious.bots",
                """{"v":1,"task":"call","request_id":"$rid","nonce":"${(++seq).toString(16).padStart(32, '0')}","ts":$ts,"expires_at":${now + 60_000},"capability":"$cap","args":{${args.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}""",
            ),
        ) as kotlinx.serialization.json.JsonObject
    }

    private fun kotlinx.serialization.json.JsonObject.st() = this["state"]?.jsonPrimitive?.content
    private fun kotlinx.serialization.json.JsonObject.cd() = this["code"]?.jsonPrimitive?.content

    @Test fun grantsApprovalsAndReceiptsSurviveARestartAndASendNeverRepeats() {
        val a = Instance()
        a.admin.addGrant("com.victorious.bots", setOf(SEND_MESSAGES, READ_CONTACTS))
        val pending = a.call("communications.send_sms", to, "send-persist-1")
        assertEquals("pending_approval", pending.st())
        assertTrue(a.admin.approve(pending["approval_id"]!!.jsonPrimitive.content))
        assertEquals("completed", a.call("communications.send_sms", to, "send-persist-1").st())
        assertEquals(1, backend.calls.size)

        now += 5_000
        val b = Instance() // process restart
        assertEquals("the grant is still there", 1, b.admin.grants().size)
        val again = b.call("communications.send_sms", to, "send-persist-1")
        assertEquals("completed", again.st())
        assertEquals("the stored receipt answered; nothing was sent twice", 1, backend.calls.size)
        assertEquals("the used approval cannot be spent again", 0, b.admin.pending().size)
    }

    @Test fun anApprovalStillWaitingSurvivesARestartAndCanThenBeDecided() {
        val a = Instance()
        a.admin.addGrant("com.victorious.bots", setOf(SEND_MESSAGES))
        val p = a.call("communications.send_sms", to, "send-persist-2")
        now += 1_000
        val b = Instance()
        assertEquals(1, b.admin.pending().size)
        assertEquals(p["approval_id"]!!.jsonPrimitive.content, b.admin.pending()[0].id)
        assertTrue(b.admin.deny(b.admin.pending()[0].id))
        assertEquals("denied", b.call("communications.send_sms", to, "send-persist-2").cd())
    }

    @Test fun revokingAndTheEmergencyStopSurviveARestart() {
        val a = Instance()
        val g = a.admin.addGrant("com.victorious.bots", setOf(READ_CONTACTS))
        assertEquals("completed", a.call("communications.list_contacts", rid = "read-persist-1").st())
        a.admin.revoke(g.id)
        a.admin.engageKill()
        now += 1_000
        val b = Instance()
        assertTrue("the stop is still on", b.kill.engaged)
        assertEquals("not_granted", b.call("communications.list_contacts", rid = "read-persist-2").cd())
        b.admin.releaseKill()
        assertFalse(Instance().kill.engaged)
    }

    @Test fun aOneTimeGrantStaysSpentAfterARestart() {
        val a = Instance()
        a.admin.addGrant("com.victorious.bots", setOf(READ_CONTACTS), oneTime = true)
        assertEquals("completed", a.call("communications.list_contacts", rid = "read-once-001").st())
        now += 1_000
        assertEquals("not_granted", Instance().call("communications.list_contacts", rid = "read-once-002").cd())
    }

    @Test fun aRequestStampedBeforeARestartIsAskedToBeSentAgainBecauseItsNonceCannotBeChecked() {
        val a = Instance()
        a.admin.addGrant("com.victorious.bots", setOf(READ_CONTACTS))
        val stampedBefore = now
        now += 1_000
        val b = Instance()
        val r = b.call("communications.list_contacts", rid = "read-stale-01", ts = stampedBefore)
        assertEquals("stale_timestamp", r.cd())
        assertEquals("completed", b.call("communications.list_contacts", rid = "read-stale-02").st())
    }

    @Test fun unreadableGrantsMeanNoAuthorityAndTheFileIsKeptNotDeleted() {
        Files.write(dir.resolve("grants.json"), "{ this is not json".toByteArray())
        val a = Instance()
        assertTrue(a.admin.grants().isEmpty())
        assertEquals("not_granted", a.call("communications.list_contacts", rid = "read-corrupt-1").cd())
        assertTrue("kept for the owner to inspect", Files.list(dir).use { s -> s.anyMatch { it.fileName.toString().startsWith("grants.json.corrupt") } })
    }

    @Test fun unreadableReceiptsStopSendsBecauseARepeatCouldNoLongerBeDetected() {
        Files.write(dir.resolve("receipts.json"), "garbage".toByteArray())
        val a = Instance()
        assertFalse(a.receipts.healthy)
        a.admin.addGrant("com.victorious.bots", setOf(SEND_MESSAGES, READ_CONTACTS))
        assertEquals("receipts_unavailable", a.call("communications.send_sms", to, "send-corrupt-1").cd())
        eqm("reading is unaffected", "completed", a.call("communications.list_contacts", rid = "read-corrupt-2").st())
        assertTrue(backend.calls.none { it.first.endsWith("send_sms") })
    }

    @Test fun grantsCanBeListedScopedRevokedPerAppAndAreValidated() {
        val a = Instance()
        a.admin.addGrant("com.victorious.bots", setOf(READ_CONTACTS), botId = "bot-1", contacts = setOf("to:5550100101"), ttlMs = 3_600_000)
        a.admin.addGrant("com.victorious.bots", setOf(SEND_MESSAGES))
        a.admin.addGrant("com.victorious.sms", setOf(READ_CONTACTS))
        assertEquals(1, a.admin.revokeAll("com.victorious.bots").let { if (it == 2) 1 else 0 })
        assertTrue(a.admin.grants().filter { it.caller == "com.victorious.sms" }.none { it.revoked })
        try {
            a.admin.addGrant("com.victorious.bots", emptySet())
            throw AssertionError("empty permissions")
        } catch (_: IllegalArgumentException) {
        }
        try {
            a.admin.addGrant("", setOf(READ_CONTACTS))
            throw AssertionError("blank app")
        } catch (_: IllegalArgumentException) {
        }
        try {
            a.admin.addGrant("x", setOf(READ_CONTACTS), ttlMs = -5)
            throw AssertionError("negative ttl")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(setOf("read_contacts"), Instance().admin.grants().first { it.botId == "bot-1" }.permissions.map { it.name.lowercase() }.toSet())
        assertEquals(1, CommsContract.JSON.parseToJsonElement(String(Files.readAllBytes(dir.resolve("grants.json")))).jsonArray.count { it.toString().contains("bot-1") })
    }
}
