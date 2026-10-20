package ai.droidcommand.flash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlashClientTest {
    private fun client(t: FakeTransport) = FlashClient(node(), t) { "req-1" }
    private val write = WriteRequest("/img.iso", "/dev/sdb", SHA, "sdb")

    @Test fun `request carries paired node, op and protocol`() {
        val t = FakeTransport { listOf(ok(result = """{"devices":[]}""")) }
        assertIs<FlashResult.Success<FlashDeviceList>>(client(t).listDevices())
        val s = t.sent.single()
        assertTrue(""""target_node_id":"$NODE_A"""" in s && """"operation":"flash.devices.list"""" in s && """"protocol_major":1""" in s, s)
    }

    @Test fun `devices decode`() {
        val t = FakeTransport { listOf(ok(result = """{"devices":[{"name":"sdb","path":"/dev/sdb","size":32,"model":"X","transport":"usb","mounted":[]}]}""")) }
        assertEquals("sdb", assertIs<FlashResult.Success<FlashDeviceList>>(client(t).listDevices()).value.devices.single().name)
    }

    @Test fun `wrong node, wrong protocol, wrong correlation and garbage are rejected`() {
        fun kind(vararg frames: String) = assertIs<FlashResult.Failure>(client(FakeTransport { frames.toList() }).capabilities()).kind
        assertEquals(FlashFailureKind.NODE_IDENTITY_MISMATCH, kind(ok(node = NODE_B)))
        assertEquals(FlashFailureKind.PROTOCOL_MISMATCH, kind(ok(major = 2)))
        assertEquals(FlashFailureKind.MALFORMED_RESPONSE, kind(ok(rid = "other")))
        assertEquals(FlashFailureKind.MALFORMED_RESPONSE, kind("not json"))
        assertEquals(FlashFailureKind.TRANSPORT, kind())
    }

    @Test fun `remote error keeps its category`() {
        val r = assertIs<FlashResult.Failure>(client(FakeTransport { listOf(err("safety_refused")) }).writeImage(write))
        assertEquals(FlashFailureKind.REMOTE_ERROR, r.kind)
        assertEquals("safety_refused", r.remoteCategory)
    }

    @Test fun `write streams progress then final, sends confirmation`() {
        val t = FakeTransport { listOf(progress(done = 1), progress(done = 3), ok(result = """{"verified":true}""")) }
        val seen = mutableListOf<Long>()
        assertIs<FlashResult.Success<*>>(client(t).writeImage(write) { seen += it.done })
        assertEquals(listOf(1L, 3L), seen)
        val s = t.sent.single()
        assertTrue(""""operation":"flash.image.write"""" in s && """"confirm":"sdb"""" in s && """"device":"/dev/sdb"""" in s, s)
    }

    @Test fun `a progress frame from another node aborts the write`() {
        val t = FakeTransport { listOf(progress(), progress(node = NODE_B), ok()) }
        assertEquals(FlashFailureKind.NODE_IDENTITY_MISMATCH, assertIs<FlashResult.Failure>(client(t).writeImage(write)).kind)
    }

    @Test fun `write refuses locally without confirmation or with bad hash and sends nothing`() {
        val t = FakeTransport { listOf(ok()) }
        assertEquals(FlashFailureKind.LOCAL_REFUSAL, assertIs<FlashResult.Failure>(client(t).writeImage(write.copy(confirm = " "))).kind)
        assertEquals(FlashFailureKind.LOCAL_REFUSAL, assertIs<FlashResult.Failure>(client(t).writeImage(write.copy(sha256 = "abc"))).kind)
        assertTrue(t.sent.isEmpty())
    }

    @Test fun `node config validation`() {
        assertFailsWith<IllegalArgumentException> { node("rf-0123456789abcdef0123456789abcdef") }
        assertFailsWith<IllegalArgumentException> { FlashNodeConfig(NODE_A, "-oProxyCommand=x", 22, "u", "/k", HOST_KEY) }
        assertFailsWith<IllegalArgumentException> { FlashNodeConfig(NODE_A, "h", 22, "u", "/k", "ssh-ed25519 AAA\nevil") }
        val nodes = parseFlashNodeConfigs("""{"nodes":[{"node_id":"$NODE_A","host":"h","user":"u","identity_file":"/k","host_key":"$HOST_KEY"}]}""")
        assertEquals(22, nodes.single().port)
        assertFailsWith<IllegalArgumentException> {
            val one = """{"node_id":"$NODE_A","host":"h","user":"u","identity_file":"/k","host_key":"$HOST_KEY"}"""
            parseFlashNodeConfigs("""{"nodes":[$one,$one]}""")
        }
    }
}
