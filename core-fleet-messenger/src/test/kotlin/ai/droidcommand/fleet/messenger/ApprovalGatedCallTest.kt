package ai.droidcommand.fleet.messenger

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApprovalGatedCallTest {
    private val sms = ExternalNodeSpec(
        id = "sms",
        displayName = "Sms",
        nodeId = NODE_ID,
        packages = listOf(PKG),
        capabilitiesOperation = "sms.capabilities.get",
        statusOperation = "sms.status",
        approvalGatedOperations = setOf("sms.send", "sms.send.status"),
    )
    private val params = buildJsonObject { put("to", JsonPrimitive("+15551234567")) }

    private fun paired(channel: FakeChannel): FleetMessengerController {
        val store = InMemoryPairedNodeStore()
        val resolver = resolverOf(identity())
        val gateway = PairedNodeGateway(store, client(channel, identity()))
        val c = FleetMessengerController(store, resolver, gateway, listOf(sms), NodeTrustVerifier(resolver), 1_000)
        assertIs<PairingResult.Paired>(c.pair("sms"))
        channel.sent.clear()
        return c
    }

    @Test
    fun `the victorsms catalog entry lists send, the conversation draft hand-off and their status as approval-gated and nothing else`() {
        assertEquals(
            setOf("sms.send", "sms.send.status", "message.draft.from_archive", "message.draft.status"),
            ExternalNodeCatalog.VICTOR_SMS.approvalGatedOperations,
        )
        assertFalse(ExternalNodeCatalog.VICTOR_SMS.isLiveAllowed("message.draft.from_archive"))
        assertFalse(ExternalNodeCatalog.VICTOR_SMS.isReadOnly("message.draft.from_archive"))
        assertTrue("message.draft.from_archive" !in ExternalNodeCatalog.VICTOR_SMS.ownerGrantedReadOperations, "a draft hand-off is never an ungated read")
        assertFalse(ExternalNodeCatalog.VICTOR_SMS.isLiveAllowed("sms.send"))
        assertTrue(ExternalNodeCatalog.all.filter { it.id != "victorsms" }.all { it.approvalGatedOperations.isEmpty() })
    }

    @Test
    fun `a gated operation reaches the node with its parameters when the node is paired and trusted`() {
        val channel = echoing { response(it) }
        val c = paired(channel)
        assertIs<LiveCallResult.Success>(c.callApprovalGated("sms", "sms.send", params))
        assertEquals(1, channel.sent.size)
        assertTrue(channel.sent.single().contains("+15551234567"))
        assertTrue(channel.sent.single().contains("sms.send"))
    }

    @Test
    fun `an operation outside the gated set is refused and nothing is sent`() {
        val channel = echoing { response(it) }
        val c = paired(channel)
        assertIs<LiveCallResult.Gated>(c.callApprovalGated("sms", "sms.wipe", params))
        assertIs<LiveCallResult.Gated>(c.callApprovalGated("nope", "sms.send", params))
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `the read-only path still refuses a gated operation`() {
        val channel = echoing { response(it) }
        val c = paired(channel)
        assertIs<LiveCallResult.Gated>(c.runReadOnly("sms", "sms.send"))
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `an unpaired node is never contacted`() {
        val channel = echoing { response(it) }
        val store = InMemoryPairedNodeStore()
        val resolver = resolverOf(identity())
        val c = FleetMessengerController(store, resolver, PairedNodeGateway(store, client(channel, identity())), listOf(sms), NodeTrustVerifier(resolver), 1_000)
        assertIs<LiveCallResult.Untrusted>(c.callApprovalGated("sms", "sms.send", params))
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `a changed signing certificate stops the call`() {
        val channel = echoing { response(it) }
        val store = InMemoryPairedNodeStore()
        val ok = resolverOf(identity())
        val c1 = FleetMessengerController(store, ok, PairedNodeGateway(store, client(channel, identity())), listOf(sms), NodeTrustVerifier(ok), 1_000)
        assertIs<PairingResult.Paired>(c1.pair("sms"))
        channel.sent.clear()
        val swapped = resolverOf(identity(OTHER_CERT))
        val c2 = FleetMessengerController(store, swapped, PairedNodeGateway(store, client(channel, identity(OTHER_CERT))), listOf(sms), NodeTrustVerifier(swapped), 1_000)
        assertIs<LiveCallResult.Untrusted>(c2.callApprovalGated("sms", "sms.send", params))
        assertTrue(channel.sent.isEmpty())
    }
}
