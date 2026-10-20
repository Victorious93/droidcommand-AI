package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteCommandEnvelopeCodecTest {
    @Test
    fun `parameters are top-level and requestId is the BridgeRequest id`() {
        val envelope = envelope(
            requestId = "bridge-id-1",
            actionRef = "lock-tablet",
            timestampMs = 1_760_920_000_000L,
        )
        val json = RemoteCommandEnvelopeCodec.encodeBridgeRequest(envelope)
        assertTrue(json.contains("\"request_id\":\"bridge-id-1\""), json)
        assertTrue(json.contains("\"operation\":\"remote.command\""), json)
        assertTrue(json.contains("\"targetNodeRef\""), json)
        assertTrue(json.contains("\"actionRef\""), json)
        assertTrue(json.contains("\"timestampMs\""), json)
        assertTrue(json.contains("\"ttlMs\""), json)
        assertTrue(json.contains("\"nonce\""), json)
        assertFalse(json.contains("\"auth\""), json)
        val parameters = RemoteCommandEnvelopeCodec.encodeParameters(envelope)
        assertFalse(parameters.contains("requestId"), parameters)
        val (op, decoded) = RemoteCommandEnvelopeCodec.decodeBridgeRequest(json)
        assertEquals(FleetRemoteEnvelope.OP_REMOTE_COMMAND, op)
        assertEquals(envelope.requestId, decoded.requestId)
        assertEquals(envelope.actionRef, decoded.actionRef)
        assertEquals(envelope.nonce, decoded.nonce)
    }

    @Test
    fun `trigger extras round-trip and stay informational`() {
        val json = RemoteCommandEnvelopeCodec.encodeParameters(envelope(actionRef = "a"))
        val decoded = RemoteCommandEnvelopeCodec.decodeParameters(json, "req")
        assertEquals("a", decoded.actionRef)
        assertEquals("+15555550100", decoded.trigger?.sourceNumber)
        assertEquals(true, decoded.trigger?.matchedKeyword)
    }

    @Test
    fun `rejects a nonce that is not 128-bit hex`() {
        assertFailsWith<IllegalArgumentException> {
            envelope(nonce = "nonce-1")
        }
    }

    @Test
    fun `operation and package constants match the frozen contract`() {
        assertEquals("remote.command", FleetRemoteEnvelope.OP_REMOTE_COMMAND)
        assertEquals("macro.step", FleetRemoteEnvelope.OP_MACRO_STEP)
        assertEquals("macro.run", FleetRemoteEnvelope.OP_MACRO_RUN)
        assertEquals("capabilities.get", FleetRemoteEnvelope.OP_CAPABILITIES_GET)
        assertEquals("com.victorious.sms", FleetRemoteEnvelope.VICTOR_SMS_PACKAGE)
        assertEquals("com.victorious.sms.action.BRIDGE", FleetRemoteEnvelope.VICTOR_SMS_BRIDGE_ACTION)
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MAJOR)
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MINOR)
        assertTrue(FleetRemoteEnvelope.VICTOR_SMS_NODE_ID.matches("victorsms-" + "ab".repeat(16)))
    }

    @Test
    fun `three bindings round-trip the same fields`() {
        val original = envelope(actionRef = "scan.run", requestId = "r1")
        val node = ExternalNodeRemoteBinding.decode(ExternalNodeRemoteBinding.encode(original)).second
        assertEquals(original.actionRef, node.actionRef)
        assertEquals(original.requestId, node.requestId)
        val companion = BoundCompanionRemoteBinding.decode(BoundCompanionRemoteBinding.encode(original))
        assertEquals(original.actionRef, companion.actionRef)
        assertEquals(original.nonce, companion.nonce)
        assertEquals(original.requestId, companion.requestId)
        val termux = TermuxIpcRemoteBinding.decodeBlob(TermuxIpcRemoteBinding.encodeBlob(original))
        assertEquals(original.actionRef, termux.actionRef)
        assertEquals(TermuxIpcRemoteBinding.EXTRA, FleetRemoteEnvelope.TERMUX_JSON_EXTRA)
    }
}
