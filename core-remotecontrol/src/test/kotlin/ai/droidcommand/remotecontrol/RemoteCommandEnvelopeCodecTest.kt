package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MINOR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteCommandEnvelopeCodecTest {
    @Test
    fun `anti-replay fields are top-level and requestId is the BridgeRequest id`() {
        val envelope = envelope(
            requestId = "bridge-id-1",
            actionRef = "lock-tablet",
            timestampMs = 1_760_920_000_000L,
        )
        val json = RemoteCommandEnvelopeCodec.encodeBridgeRequest(envelope)
        assertTrue(json.contains("\"protocol_major\":1"), json)
        assertTrue(json.contains("\"request_id\":\"bridge-id-1\""), json)
        assertTrue(json.contains("\"target_node_id\""), json)
        assertTrue(json.contains("\"timeout_ms\""), json)
        assertTrue(json.contains("\"operation\":\"remote.command\""), json)
        assertTrue(json.contains("\"parameters\""), json)
        assertFalse(json.contains("\"protocolMajor\""), json)
        assertFalse(Regex(""""requestId"\s*:""").containsMatchIn(json), json)
        assertFalse(Regex(""""targetNodeId"\s*:""").containsMatchIn(json), json)
        assertFalse(Regex(""""timeoutMs"\s*:""").containsMatchIn(json), json)
        assertTrue(json.contains("\"targetNodeRef\""), json)
        assertTrue(json.contains("\"actionRef\""), json)
        assertTrue(json.contains("\"nonce\""), json)
        assertTrue(json.contains("\"timestampMs\""), json)
        assertTrue(json.contains("\"ttlMs\""), json)
        assertTrue(json.contains("\"destructive\""), json)
        assertTrue(json.contains("\"trigger\""), json)
        assertFalse(json.contains("\"target_node_ref\""), json)
        assertFalse(json.contains("\"action_ref\""), json)
        assertFalse(json.contains("\"timestamp_ms\""), json)
        assertFalse(json.contains("\"ttl_ms\""), json)
        assertFalse(json.contains("\"auth\""), json)
        assertFalse(json.contains("\"issuedAt\""), json)
        val parameters = RemoteCommandEnvelopeCodec.encodeParameters(envelope)
        assertFalse(parameters.contains("requestId"), parameters)
        assertFalse(parameters.contains("\"auth\""), parameters)
        assertFalse(parameters.contains("\"issuedAt\""), parameters)
        assertTrue(Regex(""""timestampMs"\s*:""").containsMatchIn(parameters), parameters)
        val (op, decoded) = RemoteCommandEnvelopeCodec.decodeBridgeRequest(json)
        assertEquals(FleetRemoteEnvelope.OP_REMOTE_COMMAND, op)
        assertEquals(envelope.requestId, decoded.requestId)
        assertEquals(envelope.actionRef, decoded.actionRef)
        assertEquals(envelope.nonce, decoded.nonce)
        assertEquals(envelope.timestampMs, decoded.timestampMs)
        assertEquals(envelope.ttlMs, decoded.ttlMs)
        assertEquals(envelope.destructive, decoded.destructive)
    }

    @Test
    fun `trigger extras round-trip and stay informational`() {
        val json = RemoteCommandEnvelopeCodec.encodeParameters(envelope(actionRef = "a"))
        val decoded = RemoteCommandEnvelopeCodec.decodeParameters(json, "req")
        assertEquals("a", decoded.actionRef)
        assertEquals("+15555550100", decoded.trigger?.sourceNumber)
        assertEquals(true, decoded.trigger?.matchedKeyword)
        assertFalse(json.contains("\"auth\""), json)
    }

    @Test
    fun `missing targetNodeRef in parameters is invalid`() {
        assertFailsWith<IllegalArgumentException> {
            RemoteCommandEnvelopeCodec.decodeParameters(
                """{"actionRef":"ping","nonce":"$TEST_NONCE","timestampMs":1,"ttlMs":120000}""",
                "req",
            )
        }
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
        assertEquals("sms.remote_command.forward", FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD)
        assertEquals("macro.step", FleetRemoteEnvelope.OP_MACRO_STEP)
        assertEquals("macro.run", FleetRemoteEnvelope.OP_MACRO_RUN)
        assertEquals("capabilities.get", FleetRemoteEnvelope.OP_CAPABILITIES_GET)
        assertEquals("node.status.get", FleetRemoteEnvelope.OP_NODE_STATUS_GET)
        assertEquals("com.victorious.sms", FleetRemoteEnvelope.VICTOR_SMS_PACKAGE)
        assertEquals("com.victorious.sms.action.BRIDGE", FleetRemoteEnvelope.VICTOR_SMS_BRIDGE_ACTION)
        assertEquals("ai.droidcommand.app.action.BRIDGE", FleetRemoteEnvelope.CONTROLLER_BRIDGE_ACTION)
        assertEquals("com.victorious.spoof", FleetRemoteEnvelope.VICTOR_SPOOF_PACKAGE)
        assertEquals("com.identityshield.companion", FleetRemoteEnvelope.KYC_PACKAGE)
        assertEquals(
            "com.identityshield.companion.action.BRIDGE",
            FleetRemoteEnvelope.KYC_BRIDGE_ACTION,
        )
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MAJOR)
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MINOR)
        assertEquals(1, BRIDGE_PROTOCOL_MINOR)
        assertTrue(FleetRemoteEnvelope.VICTOR_SMS_NODE_ID.matches("victorsms-" + "ab".repeat(16)))
    }

    @Test
    fun `three bindings round-trip the same top-level fields`() {
        val original = envelope(actionRef = "scan.run", requestId = "r1")
        val node = ExternalNodeRemoteBinding.decode(ExternalNodeRemoteBinding.encode(original)).second
        assertEquals(original.actionRef, node.actionRef)
        assertEquals(original.requestId, node.requestId)
        assertEquals(original.timestampMs, node.timestampMs)
        assertEquals(original.nonce, node.nonce)
        val companion = BoundCompanionRemoteBinding.decode(BoundCompanionRemoteBinding.encode(original))
        assertEquals(original.actionRef, companion.actionRef)
        assertEquals(original.nonce, companion.nonce)
        assertEquals(original.requestId, companion.requestId)
        assertEquals(original.timestampMs, companion.timestampMs)
        val termuxJson = TermuxIpcRemoteBinding.encodeBlob(original)
        val termux = TermuxIpcRemoteBinding.decodeBlob(termuxJson)
        assertEquals(original.actionRef, termux.actionRef)
        assertEquals(original.timestampMs, termux.timestampMs)
        assertTrue(termuxJson.contains("\"timestampMs\""))
        assertFalse(termuxJson.contains("\"auth\""), termuxJson)
        assertFalse(termuxJson.contains("\"issuedAt\""), termuxJson)
        assertEquals(TermuxIpcRemoteBinding.EXTRA, FleetRemoteEnvelope.TERMUX_JSON_EXTRA)
    }
}
