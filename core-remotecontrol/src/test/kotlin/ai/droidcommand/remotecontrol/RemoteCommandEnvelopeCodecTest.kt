package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MINOR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteCommandEnvelopeCodecTest {
    @Test
    fun `auth is nested and requestId is the BridgeRequest id`() {
        val envelope = envelope(
            requestId = "bridge-id-1",
            actionRef = "lock-tablet",
            issuedAt = 1_760_920_000_000L,
        )
        val json = RemoteCommandEnvelopeCodec.encodeBridgeRequest(envelope)
        assertTrue(json.contains("\"request_id\":\"bridge-id-1\""), json)
        assertTrue(json.contains("\"operation\":\"remote.command\""), json)
        assertTrue(json.contains("\"targetNodeRef\""), json)
        assertTrue(json.contains("\"actionRef\""), json)
        assertTrue(json.contains("\"auth\""), json)
        assertTrue(json.contains("\"issuedAt\""), json)
        assertTrue(json.contains("\"nonce\""), json)
        assertTrue(json.contains("\"ttlMs\""), json)
        val parameters = RemoteCommandEnvelopeCodec.encodeParameters(envelope)
        assertFalse(parameters.contains("requestId"), parameters)
        assertTrue(parameters.contains("\"auth\""), parameters)
        assertFalse(Regex(""""timestampMs"\s*:""").containsMatchIn(parameters), parameters)
        val (op, decoded) = RemoteCommandEnvelopeCodec.decodeBridgeRequest(json)
        assertEquals(FleetRemoteEnvelope.OP_REMOTE_COMMAND, op)
        assertEquals(envelope.requestId, decoded.requestId)
        assertEquals(envelope.actionRef, decoded.actionRef)
        assertEquals(envelope.nonce, decoded.nonce)
        assertEquals(envelope.issuedAt, decoded.auth.issuedAt)
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
    fun `rejects a nonce that is not 128-bit hex or base64`() {
        assertFailsWith<IllegalArgumentException> {
            envelope(nonce = "nonce-1")
        }
    }

    @Test
    fun `operation and package constants match the ratified contract`() {
        assertEquals("remote.command", FleetRemoteEnvelope.OP_REMOTE_COMMAND)
        assertEquals("macro.step", FleetRemoteEnvelope.OP_MACRO_STEP)
        assertEquals("macro.run", FleetRemoteEnvelope.OP_MACRO_RUN)
        assertEquals("capabilities.get", FleetRemoteEnvelope.OP_CAPABILITIES_GET)
        assertEquals("com.victorious.sms", FleetRemoteEnvelope.VICTOR_SMS_PACKAGE)
        assertEquals("com.victorious.sms.action.BRIDGE", FleetRemoteEnvelope.VICTOR_SMS_BRIDGE_ACTION)
        assertEquals("ai.droidcommand.app.action.BRIDGE", FleetRemoteEnvelope.CONTROLLER_BRIDGE_ACTION)
        assertEquals("com.victorious.spoof", FleetRemoteEnvelope.VICTOR_SPOOF_PACKAGE)
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MAJOR)
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MINOR)
        assertEquals(1, BRIDGE_PROTOCOL_MINOR)
        assertTrue(FleetRemoteEnvelope.VICTOR_SMS_NODE_ID.matches("victorsms-" + "ab".repeat(16)))
    }

    @Test
    fun `three bindings round-trip the same fields including nested auth`() {
        val original = envelope(actionRef = "scan.run", requestId = "r1")
        val node = ExternalNodeRemoteBinding.decode(ExternalNodeRemoteBinding.encode(original)).second
        assertEquals(original.actionRef, node.actionRef)
        assertEquals(original.requestId, node.requestId)
        assertEquals(original.auth.issuedAt, node.auth.issuedAt)
        val companion = BoundCompanionRemoteBinding.decode(BoundCompanionRemoteBinding.encode(original))
        assertEquals(original.actionRef, companion.actionRef)
        assertEquals(original.nonce, companion.nonce)
        assertEquals(original.requestId, companion.requestId)
        assertEquals(original.issuedAt, companion.issuedAt)
        val termux = TermuxIpcRemoteBinding.decodeBlob(TermuxIpcRemoteBinding.encodeBlob(original))
        assertEquals(original.actionRef, termux.actionRef)
        assertTrue(TermuxIpcRemoteBinding.encodeBlob(original).contains("\"auth\""))
        assertEquals(TermuxIpcRemoteBinding.EXTRA, FleetRemoteEnvelope.TERMUX_JSON_EXTRA)
    }
}
