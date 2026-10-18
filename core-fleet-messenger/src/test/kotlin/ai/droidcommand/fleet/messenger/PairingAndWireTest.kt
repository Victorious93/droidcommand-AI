package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingAndWireTest {
    @Test
    fun `wire constants and default action follow the documented protocol`() {
        assertEquals(1, MessengerWire.WHAT_REQUEST)
        assertEquals(2, MessengerWire.WHAT_RESPONSE)
        assertEquals("request", MessengerWire.KEY_REQUEST)
        assertEquals("response", MessengerWire.KEY_RESPONSE)
        assertEquals(10_000, MessengerWire.DEFAULT_TIMEOUT_MS)
        assertEquals("ai.victorious.filemanager.action.BRIDGE", PairedNode.of("ai.victorious.filemanager", "fm-1", CERT).action)
    }

    @Test
    fun `a custom action overrides the default and is validated`() {
        assertEquals("com.example.CUSTOM", PairedNode.of(PKG, NODE_ID, CERT, "com.example.CUSTOM").action)
        assertFailsWith<IllegalArgumentException> { PairedNode.of(PKG, NODE_ID, CERT, "bad action!") }
    }

    @Test
    fun `fingerprints are normalised and validated`() {
        val colon = CERT.uppercase().chunked(2).joinToString(":")
        assertEquals(CERT, PairedNode.of(PKG, NODE_ID, colon).expectedCertSha256)
        assertFailsWith<IllegalArgumentException> { PairedNode.of(PKG, NODE_ID, "abcd") }
        assertFailsWith<IllegalArgumentException> { PairedNode(PKG, NODE_ID, CERT.uppercase()) }
    }

    @Test
    fun `package and node id are validated`() {
        assertFailsWith<IllegalArgumentException> { PairedNode.of("nodot", NODE_ID, CERT) }
        assertFailsWith<IllegalArgumentException> { PairedNode.of("-bad.pkg", NODE_ID, CERT) }
        assertFailsWith<IllegalArgumentException> { PairedNode.of(PKG, "", CERT) }
        assertFailsWith<IllegalArgumentException> { PairedNode.of(PKG, "id with space", CERT) }
    }

    @Test
    fun `pairing document round-trips and rejects duplicates and bad entries`() {
        val nodes = listOf(pairedNode(), PairedNode.of("com.other.node", "other-1", OTHER_CERT, "com.other.node.action.BRIDGE"))
        assertEquals(nodes, PairedNodeCodec.decode(PairedNodeCodec.encode(nodes)))
        assertEquals(emptyList(), PairedNodeCodec.decode("""{"nodes":[]}"""))

        assertFailsWith<IllegalArgumentException> { PairedNodeCodec.encode(listOf(pairedNode(), pairedNode())) }
        val dupPkg = """{"nodes":[
            {"package":"$PKG","node_id":"a-1","signing_cert_sha256":"$CERT"},
            {"package":"$PKG","node_id":"a-2","signing_cert_sha256":"$CERT"}]}"""
        assertFailsWith<IllegalArgumentException> { PairedNodeCodec.decode(dupPkg) }
        val badCert = """{"nodes":[{"package":"$PKG","node_id":"a-1","signing_cert_sha256":"nothex"}]}"""
        assertFailsWith<Exception> { PairedNodeCodec.decode(badCert) }
        assertFailsWith<Exception> { PairedNodeCodec.decode("""{"nodes":[],"extra":1}""") }
    }

    @Test
    fun `store pairs and unpairs and finds by node id`() {
        val store = InMemoryPairedNodeStore()
        assertTrue(store.all().isEmpty())
        assertNull(store.find(NODE_ID))
        store.pair(pairedNode())
        assertEquals(pairedNode(), store.find(NODE_ID))
        assertFailsWith<IllegalArgumentException> { store.pair(pairedNode()) }
        assertTrue(store.unpair(NODE_ID))
        assertFalse(store.unpair(NODE_ID))
    }

    @Test
    fun `gateway with nothing paired refuses and never touches the channel`() {
        val channel = echoing { response(it) }
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(), client(channel))
        val f = assertIs<RootForgeResult.Failure>(gateway.call(NODE_ID, "node.ping"))
        assertEquals(FailureKind.UNTRUSTED_NODE, f.kind)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `gateway dispatches a paired node through the trusted client`() {
        val channel = echoing { response(it) }
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(listOf(pairedNode())), client(channel))
        assertIs<RootForgeResult.Success<*>>(gateway.call(NODE_ID, "node.ping"))
        assertEquals(1, channel.opens)
    }

    @Test
    fun `a paired node whose package was re-signed is refused through the gateway`() {
        val channel = echoing { response(it) }
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(listOf(pairedNode())), client(channel, identity(OTHER_CERT)))
        assertEquals(FailureKind.UNTRUSTED_NODE, assertIs<RootForgeResult.Failure>(gateway.call(NODE_ID, "node.ping")).kind)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `fire-and-forget and pollStatus send empty parameters and map up to status`() {
        val channel = echoing { req ->
            if (req.operation.endsWith(".up")) response(req, result = null) else response(req)
        }
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(listOf(pairedNode())), client(channel))
        assertIs<RootForgeResult.Success<*>>(gateway.fireAndForget(NODE_ID, "mesh.up"))
        assertEquals("mesh.up", decodeRequest(channel.sent[0]).operation)
        assertEquals(NodeOperations.EMPTY_PARAMETERS, decodeRequest(channel.sent[0]).parameters)

        assertIs<RootForgeResult.Success<*>>(gateway.pollStatus(NODE_ID, "mesh.up"))
        assertEquals("mesh.status", decodeRequest(channel.sent[1]).operation)
        assertEquals(NodeOperations.EMPTY_PARAMETERS, decodeRequest(channel.sent[1]).parameters)
    }

    @Test
    fun `up suffix detection and status mapping`() {
        assertTrue(NodeOperations.isFireAndForget("mesh.up"))
        assertTrue(NodeOperations.isFireAndForget("rootforge.session.up"))
        assertFalse(NodeOperations.isFireAndForget("mesh.status"))
        assertFalse(NodeOperations.isFireAndForget("up"))
        assertEquals("mesh.status", NodeOperations.statusOperation("mesh.up"))
        assertFailsWith<IllegalArgumentException> { NodeOperations.statusOperation("mesh.status") }
    }
}

class PendingReplyTest {
    @Test
    fun `await times out when nothing arrives`() {
        val r = PendingReply().await(20)
        assertIs<MessengerTimeoutException>(r.exceptionOrNull())
    }

    @Test
    fun `the first outcome wins and later ones are ignored`() {
        val p = PendingReply()
        assertTrue(p.complete("first"))
        assertFalse(p.complete("second"))
        assertFalse(p.fail(RuntimeException("late")))
        assertEquals("first", p.await(10).getOrThrow())
    }

    @Test
    fun `a reply from another thread is delivered`() {
        val p = PendingReply()
        Thread { p.complete("hi") }.start()
        assertEquals("hi", p.await(2_000).getOrThrow())
    }

    @Test
    fun `a failure is surfaced`() {
        val p = PendingReply()
        p.fail(java.io.IOException("service disconnected"))
        assertEquals("service disconnected", p.await(10).exceptionOrNull()?.message)
    }
}
