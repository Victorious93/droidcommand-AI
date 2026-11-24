package ai.droidcommand.app.communications

import ai.droidcommand.communications.ArchiveAnswer
import ai.droidcommand.communications.ArchiveWire
import ai.droidcommand.communications.VictorSmsCommsBackend
import ai.droidcommand.fleet.messenger.ExternalNodeCatalog
import ai.droidcommand.fleet.messenger.LiveCallResult
import ai.droidcommand.rootforge.FailureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ControllerArchiveChannelTest {
    private val params = JsonObject(mapOf("conversation" to JsonPrimitive("c")))
    private fun channel(result: LiveCallResult) = ControllerArchiveChannel { _, _ -> result }
    private val op = ArchiveWire.READ

    @Test fun `the two repositories name the same five operations`() {
        // The spec lives in core-fleet-messenger, the client in core-communications; only :app sees both.
        assertEquals(ArchiveWire.OPS, ExternalNodeCatalog.VICTOR_SMS.ownerGrantedReadOperations)
        assertEquals(VictorSmsCommsBackend.OPERATIONS, ExternalNodeCatalog.VICTOR_SMS.ownerGrantedReadOperations)
    }

    @Test fun `a successful read hands over the payload`() {
        val payload = JsonObject(mapOf("count" to JsonPrimitive(1)))
        assertEquals(ArchiveAnswer.Ok(payload), channel(LiveCallResult.Success(op, payload)).call(op, params))
    }

    @Test fun `the node saying no keeps its category, and a missing category still says no`() {
        assertEquals(ArchiveAnswer.Refused("not_found", "gone"), channel(LiveCallResult.RemoteError(op, "gone", "not_found")).call(op, params))
        assertEquals(ArchiveAnswer.Refused("refused", "x"), channel(LiveCallResult.RemoteError(op, "x", null)).call(op, params))
    }

    @Test fun `not paired, changed certificate, silence and transport trouble all mean unavailable`() {
        assertIs<ArchiveAnswer.Unavailable>(channel(LiveCallResult.Untrusted(op, "VictorSMS is not paired")).call(op, params))
        assertIs<ArchiveAnswer.Unavailable>(channel(LiveCallResult.Timeout(op, "no reply")).call(op, params))
        assertIs<ArchiveAnswer.Unavailable>(channel(LiveCallResult.Failed(op, FailureKind.TRANSPORT, "bindService refused")).call(op, params))
    }

    @Test fun `a reply the validator rejected is a failure, never data`() {
        for (kind in listOf(FailureKind.MALFORMED_RESPONSE, FailureKind.PROTOCOL_MISMATCH, FailureKind.NODE_IDENTITY_MISMATCH, FailureKind.UNTRUSTED_NODE)) {
            val a = channel(LiveCallResult.Failed(op, kind, "bad")).call(op, params)
            assertIs<ArchiveAnswer.Failed>(a, kind.name)
            assertTrue((a as ArchiveAnswer.Failed).message.contains("bad"))
        }
        assertIs<ArchiveAnswer.Failed>(channel(LiveCallResult.Gated(op, "not allowed")).call(op, params))
    }

    @Test fun `the operation and parameters reach the sender unchanged`() {
        var seen: Pair<String, JsonObject>? = null
        ControllerArchiveChannel { o, p ->
            seen = o to p
            LiveCallResult.Success(o, JsonObject(emptyMap()))
        }.call(op, params)
        assertEquals(op to params, seen)
    }
}
