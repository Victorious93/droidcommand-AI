package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.BridgeErrorBody
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Victorious Framework: parameterised archive reads reach only the one node that owns them, only after the usual trust check. */
class OwnerGrantedReadTest {
    private val archiveOps = setOf("comms.conversation.read", "comms.messages.search")
    private val sms = ExternalNodeSpec(
        id = "sms",
        displayName = "Sms",
        nodeId = NODE_ID,
        packages = listOf(PKG),
        capabilitiesOperation = "sms.capabilities.get",
        statusOperation = "sms.status",
        approvalGatedOperations = setOf("sms.send"),
        ownerGrantedReadOperations = archiveOps,
    )
    private val params = buildJsonObject { put("conversation", JsonPrimitive("0123456789abcdef0123456789abcdef")) }

    private fun paired(channel: FakeChannel): FleetMessengerController {
        val store = InMemoryPairedNodeStore()
        val resolver = resolverOf(identity())
        val c = FleetMessengerController(store, resolver, PairedNodeGateway(store, client(channel, identity())), listOf(sms), NodeTrustVerifier(resolver), 1_000)
        assertIs<PairingResult.Paired>(c.pair("sms"))
        channel.sent.clear()
        return c
    }

    @Test
    fun `the victorsms entry lists exactly the five archive reads and no other member has any`() {
        assertEquals(
            setOf("comms.capabilities", "comms.conversations.list", "comms.conversation.read", "comms.messages.search", "comms.context.retrieve"),
            ExternalNodeCatalog.VICTOR_SMS.ownerGrantedReadOperations,
        )
        assertTrue(ExternalNodeCatalog.all.filter { it.id != "victorsms" }.all { it.ownerGrantedReadOperations.isEmpty() })
        assertFalse(ExternalNodeCatalog.VICTOR_SMS.isLiveAllowed("comms.conversation.read"), "never through the parameterless path")
    }

    @Test
    fun `a spec cannot file one operation under two lists`() {
        assertFailsWith<IllegalArgumentException> { sms.copy(ownerGrantedReadOperations = setOf("sms.send")) }
        assertFailsWith<IllegalArgumentException> { sms.copy(ownerGrantedReadOperations = setOf("sms.status")) }
    }

    @Test
    fun `a listed read reaches the paired trusted node with its parameters`() {
        val channel = echoing { response(it) }
        val c = paired(channel)
        assertIs<LiveCallResult.Success>(c.callOwnerGrantedRead("sms", "comms.conversation.read", params))
        assertEquals(1, channel.sent.size)
        assertTrue(channel.sent.single().contains("comms.conversation.read"))
        assertTrue(channel.sent.single().contains("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `anything outside the owner-granted set is refused and nothing is sent`() {
        val channel = echoing { response(it) }
        val c = paired(channel)
        assertIs<LiveCallResult.Gated>(c.callOwnerGrantedRead("sms", "sms.send", params), "a send is not a read")
        assertIs<LiveCallResult.Gated>(c.callOwnerGrantedRead("sms", "comms.delete_everything", params))
        assertIs<LiveCallResult.Gated>(c.callOwnerGrantedRead("nope", "comms.conversation.read", params))
        assertIs<LiveCallResult.Gated>(c.callApprovalGated("sms", "comms.conversation.read", params), "the send path cannot be used for reads")
        assertIs<LiveCallResult.Gated>(c.runReadOnly("sms", "comms.conversation.read"), "the parameterless path cannot carry them")
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `an unpaired node is never contacted and a changed signing certificate stops the call`() {
        val channel = echoing { response(it) }
        val store = InMemoryPairedNodeStore()
        val ok = resolverOf(identity())
        val unpaired = FleetMessengerController(store, ok, PairedNodeGateway(store, client(channel, identity())), listOf(sms), NodeTrustVerifier(ok), 1_000)
        assertIs<LiveCallResult.Untrusted>(unpaired.callOwnerGrantedRead("sms", "comms.conversation.read", params))
        assertTrue(channel.sent.isEmpty())

        assertIs<PairingResult.Paired>(unpaired.pair("sms"))
        channel.sent.clear()
        val swapped = resolverOf(identity(OTHER_CERT))
        val changed = FleetMessengerController(store, swapped, PairedNodeGateway(store, client(channel, identity(OTHER_CERT))), listOf(sms), NodeTrustVerifier(swapped), 1_000)
        assertIs<LiveCallResult.Untrusted>(changed.callOwnerGrantedRead("sms", "comms.conversation.read", params))
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `the node saying no comes back as a remote error with its category`() {
        val channel = echoing { response(it, ok = false, result = null, error = BridgeErrorBody("permission_denied", "not granted")) }
        val c = paired(channel)
        val r = assertIs<LiveCallResult.RemoteError>(c.callOwnerGrantedRead("sms", "comms.messages.search", params))
        assertEquals("permission_denied", r.category)
    }
}
