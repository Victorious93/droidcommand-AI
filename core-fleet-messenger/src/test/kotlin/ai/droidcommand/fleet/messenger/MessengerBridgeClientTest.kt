package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.BridgeErrorBody
import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessengerBridgeClientTest {
    private fun failure(r: RootForgeResult<JsonObject>) = assertIs<RootForgeResult.Failure>(r)

    @Test
    fun `request envelope round-trips and carries the wire fields`() {
        val channel = echoing { response(it) }
        val params = buildJsonObject { put("path", JsonPrimitive("/sdcard")) }
        val result = client(channel).call(pairedNode(), "node.list", params, 4_000)

        val sent = channel.sent.single()
        val raw = messengerJson.parseToJsonElement(sent).jsonObject
        assertEquals(
            setOf("protocol_major", "request_id", "target_node_id", "operation", "timeout_ms", "parameters"),
            raw.keys,
        )
        assertEquals(1, raw.getValue("protocol_major").jsonPrimitive.int)
        assertEquals("dca-test-1", raw.getValue("request_id").jsonPrimitive.content)
        val decoded = decodeRequest(sent)
        assertEquals(NODE_ID, decoded.targetNodeId)
        assertEquals("node.list", decoded.operation)
        assertEquals(4_000, decoded.timeoutMs)
        assertEquals(params, decoded.parameters)
        assertEquals(okResult(), assertIs<RootForgeResult.Success<JsonObject>>(result).value)
    }

    @Test
    fun `defaults to a 10 second timeout and waits a grace period for the reply`() {
        val channel = echoing { response(it) }
        client(channel).call(pairedNode(), "node.ping")
        assertEquals(10_000, decodeRequest(channel.sent.single()).timeoutMs)
        assertEquals(10_000L + MessengerWire.REPLY_GRACE_MS, channel.exchangeTimeoutMs)
        assertEquals(1, channel.closed)
        assertEquals(NodeOperations.EMPTY_PARAMETERS, decodeRequest(channel.sent.single()).parameters)
    }

    @Test
    fun `generated request ids look like dca-uuid`() {
        val channel = echoing { response(it) }
        val c: ExternalNodeTransport = MessengerBridgeClient(NodeTrustVerifier(resolverOf(identity())), channel)
        c.call(pairedNode(), "node.ping")
        assertTrue(Regex("^dca-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(decodeRequest(channel.sent.single()).requestId))
    }

    @Test
    fun `unknown response fields are tolerated`() {
        val channel = FakeChannel {
            val id = decodeRequest(it).requestId
            Result.success("""{"protocol_major":1,"request_id":"$id","node_id":"$NODE_ID","ok":true,"result":{"a":1},"future_field":[1,2]}""")
        }
        assertIs<RootForgeResult.Success<JsonObject>>(client(channel).call(pairedNode(), "x"))
    }

    @Test
    fun `bad protocol major is PROTOCOL_MISMATCH`() {
        val f = failure(client(echoing { response(it, major = 2) }).call(pairedNode(), "x"))
        assertEquals(FailureKind.PROTOCOL_MISMATCH, f.kind)
    }

    @Test
    fun `wrong node id is NODE_IDENTITY_MISMATCH`() {
        val f = failure(client(echoing { response(it, nodeId = "node-someone-else") }).call(pairedNode(), "x"))
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, f.kind)
    }

    @Test
    fun `missing node id is NODE_IDENTITY_MISMATCH`() {
        val f = failure(client(echoing { response(it, nodeId = null) }).call(pairedNode(), "x"))
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, f.kind)
    }

    @Test
    fun `mismatched or missing request id is MALFORMED_RESPONSE`() {
        assertEquals(
            FailureKind.MALFORMED_RESPONSE,
            failure(client(echoing { response(it, requestId = "dca-other") }).call(pairedNode(), "x")).kind,
        )
        assertEquals(
            FailureKind.MALFORMED_RESPONSE,
            failure(client(echoing { response(it, requestId = null) }).call(pairedNode(), "x")).kind,
        )
    }

    @Test
    fun `ok false is REMOTE_ERROR with the node's category`() {
        val f = failure(
            client(echoing { response(it, ok = false, result = null, error = BridgeErrorBody("unauthorized_caller", "caller not allowed")) })
                .call(pairedNode(), "x"),
        )
        assertEquals(FailureKind.REMOTE_ERROR, f.kind)
        assertEquals("unauthorized_caller", f.remoteCategory)
        assertEquals("caller not allowed", f.message)
    }

    @Test
    fun `ok false without an error body is still REMOTE_ERROR`() {
        val f = failure(client(echoing { response(it, ok = false, result = null) }).call(pairedNode(), "x"))
        assertEquals(FailureKind.REMOTE_ERROR, f.kind)
        assertNull(f.remoteCategory)
    }

    @Test
    fun `ok true without a result is MALFORMED_RESPONSE`() {
        val f = failure(client(echoing { response(it, ok = true, result = null) }).call(pairedNode(), "x"))
        assertEquals(FailureKind.MALFORMED_RESPONSE, f.kind)
    }

    @Test
    fun `garbage and oversize replies are MALFORMED_RESPONSE`() {
        assertEquals(FailureKind.MALFORMED_RESPONSE, failure(client(FakeChannel { Result.success("not json") }).call(pairedNode(), "x")).kind)
        assertEquals(FailureKind.MALFORMED_RESPONSE, failure(client(FakeChannel { Result.success("{}") }).call(pairedNode(), "x")).kind)
        val huge = "x".repeat(MessengerWire.MAX_RESPONSE_CHARS + 1)
        assertEquals(FailureKind.MALFORMED_RESPONSE, failure(client(FakeChannel { Result.success(huge) }).call(pairedNode(), "x")).kind)
    }

    @Test
    fun `validation order is major then node then request id`() {
        val f1 = failure(client(echoing { response(it, major = 9, nodeId = "other", requestId = "dca-x") }).call(pairedNode(), "x"))
        assertEquals(FailureKind.PROTOCOL_MISMATCH, f1.kind)
        val f2 = failure(client(echoing { response(it, nodeId = "other", requestId = "dca-x") }).call(pairedNode(), "x"))
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, f2.kind)
        val f3 = failure(client(echoing { response(it, requestId = "dca-x", ok = false, result = null) }).call(pairedNode(), "x"))
        assertEquals(FailureKind.MALFORMED_RESPONSE, f3.kind)
    }

    @Test
    fun `connect and exchange failures are TRANSPORT and the session is closed`() {
        val noOpen = FakeChannel { Result.success("") }.apply { openFailure = java.io.IOException("bind refused") }
        val f1 = failure(client(noOpen).call(pairedNode(), "x"))
        assertEquals(FailureKind.TRANSPORT, f1.kind)
        assertTrue("bind refused" in f1.message)

        val broken = FakeChannel { Result.failure(java.io.IOException("service died")) }
        assertEquals(FailureKind.TRANSPORT, failure(client(broken).call(pairedNode(), "x")).kind)
        assertEquals(1, broken.closed)
    }

    @Test
    fun `no reply within the deadline is a TRANSPORT timeout`() {
        val pending = PendingReply()
        val channel = FakeChannel { pending.await(50) }
        val f = failure(client(channel).call(pairedNode(), "x", timeoutMs = 1))
        assertEquals(FailureKind.TRANSPORT, f.kind)
        assertTrue("no reply within" in f.message)
        assertEquals(1, channel.closed)
        assertEquals(1L + MessengerWire.REPLY_GRACE_MS, channel.exchangeTimeoutMs)
    }

    @Test
    fun `invalid arguments are rejected before anything happens`() {
        val channel = echoing { response(it) }
        val c = client(channel)
        for (bad in listOf(0, -1, MessengerWire.MAX_TIMEOUT_MS + 1)) {
            kotlin.runCatching { c.call(pairedNode(), "x", timeoutMs = bad) }.let { assertTrue(it.isFailure) }
        }
        kotlin.runCatching { c.call(pairedNode(), " ") }.let { assertTrue(it.isFailure) }
        assertEquals(0, channel.opens)
    }

    @Test
    fun `ok true without a result is ACK success for fire-and-forget up ops`() {
        val channel = echoing { response(it, ok = true, result = null) }
        val result = client(channel).call(pairedNode(), "mesh.up")
        assertEquals(NodeOperations.EMPTY_PARAMETERS, assertIs<RootForgeResult.Success<JsonObject>>(result).value)
        assertEquals(NodeOperations.EMPTY_PARAMETERS, decodeRequest(channel.sent.single()).parameters)
        assertEquals(ReplyExpectation.ACK, NodeOperations.expectationFor("mesh.up"))
    }

    @Test
    fun `fire-and-forget still validates protocol node and request id before treating an ack as success`() {
        assertEquals(FailureKind.PROTOCOL_MISMATCH, failure(client(echoing { response(it, major = 9, result = null) }).call(pairedNode(), "mesh.up")).kind)
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, failure(client(echoing { response(it, nodeId = "other", result = null) }).call(pairedNode(), "mesh.up")).kind)
        assertEquals(FailureKind.MALFORMED_RESPONSE, failure(client(echoing { response(it, requestId = "dca-x", result = null) }).call(pairedNode(), "mesh.up")).kind)
    }

    @Test
    fun `forcing RESULT on an up op still rejects ok true with no result`() {
        val f = failure(client(echoing { response(it, result = null) }).call(pairedNode(), "mesh.up", expect = ReplyExpectation.RESULT))
        assertEquals(FailureKind.MALFORMED_RESPONSE, f.kind)
    }

    @Test
    fun `node error categories unauthorized_caller external_control_disabled and invalid_parameters surface as REMOTE_ERROR`() {
        for (category in listOf("unauthorized_caller", "external_control_disabled", "invalid_parameters")) {
            val f = failure(
                client(echoing { response(it, ok = false, result = null, error = BridgeErrorBody(category, "denied")) })
                    .call(pairedNode(), "mesh.up"),
            )
            assertEquals(FailureKind.REMOTE_ERROR, f.kind)
            assertEquals(category, f.remoteCategory)
        }
    }

    @Test
    fun `a stale reply cannot satisfy a later call`() {
        val channel = FakeChannel { Result.success(response(decodeRequest(it).copy(requestId = "dca-from-an-earlier-call"))) }
        val f = failure(client(channel, requestId = "dca-now").call(pairedNode(), "x"))
        assertEquals(FailureKind.MALFORMED_RESPONSE, f.kind)
        assertFalse(f.message.isBlank())
    }
}
