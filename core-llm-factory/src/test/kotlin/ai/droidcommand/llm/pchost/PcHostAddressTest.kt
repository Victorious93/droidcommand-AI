package ai.droidcommand.llm.pchost

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcHostAddressTest {
    private fun ok(kind: PcHostKind, input: String) = assertIs<PcHostAddressParse.Ok>(PcHostAddress.parse(kind, input)).address

    private fun rejected(kind: PcHostKind, input: String) = assertIs<PcHostAddressParse.Rejected>(PcHostAddress.parse(kind, input)).reason

    @Test
    fun `a bare LAN number gets http and Ollama's default port`() {
        assertEquals("http://192.168.1.20:11434", ok(PcHostKind.OLLAMA, " 192.168.1.20 ").base)
        assertEquals("http://192.168.1.20:8000", ok(PcHostKind.OLLAMA, "192.168.1.20:8000").base)
        assertEquals("http://192.168.1.20", ok(PcHostKind.OPENAI_COMPATIBLE, "192.168.1.20").base)
    }

    @Test
    fun `the base URL other clients ask for is accepted as the same server`() {
        assertEquals("http://10.0.0.5:1234", ok(PcHostKind.OPENAI_COMPATIBLE, "http://10.0.0.5:1234/v1/").base)
        assertEquals("http://10.0.0.5:1234/proxy", ok(PcHostKind.OPENAI_COMPATIBLE, "http://10.0.0.5:1234/proxy/v1").base)
        assertEquals("http://10.0.0.5:11434", ok(PcHostKind.OLLAMA, "http://10.0.0.5:11434/api").base)
        assertEquals("http://10.0.0.5:11434", ok(PcHostKind.OLLAMA, "http://10.0.0.5:11434/v1").base)
    }

    @Test
    fun `VPN and IPv6 private addresses may use plain http`() {
        assertEquals("http://100.101.102.103:11434", ok(PcHostKind.OLLAMA, "100.101.102.103").base) // Tailscale / Headscale range
        assertEquals("http://[fd7a:115c:a1e0::1]:11434", ok(PcHostKind.OLLAMA, "http://[fd7a:115c:a1e0::1]").base)
        assertEquals("http://172.20.0.9:8080", ok(PcHostKind.OPENAI_COMPATIBLE, "172.20.0.9:8080").base)
        assertNotNull(ok(PcHostKind.OLLAMA, "10.8.0.2").warning)
    }

    @Test
    fun `a public number over plain http is refused and https is accepted`() {
        assertTrue(rejected(PcHostKind.OLLAMA, "http://8.8.8.8:11434").contains("own network or VPN"))
        assertTrue(rejected(PcHostKind.OLLAMA, "100.128.0.1").contains("own network or VPN")) // just outside 100.64/10
        val https = ok(PcHostKind.OLLAMA, "https://pc.example.com")
        assertEquals("https://pc.example.com", https.base) // no Ollama port forced onto an https reverse proxy
        assertNull(https.warning)
    }

    @Test
    fun `addresses that could leak a secret or reach a cloud machine are refused`() {
        assertTrue(rejected(PcHostKind.OLLAMA, "http://me:secret@192.168.1.20").contains("key box"))
        rejected(PcHostKind.OLLAMA, "http://169.254.169.254")
        rejected(PcHostKind.OLLAMA, "ftp://192.168.1.20")
        rejected(PcHostKind.OLLAMA, "http://192.168.1.20/?x=1")
        rejected(PcHostKind.OLLAMA, "")
    }

    @Test
    fun `a name over plain http is allowed only when it resolves to a private address`() {
        val named = ok(PcHostKind.OLLAMA, "my-pc.tailnet.example")
        assertEquals("http://my-pc.tailnet.example:11434", named.base)
        assertNull(named.cleartextProblem { listOf(InetAddress.getByName("100.64.0.7")) })
        assertEquals(PcHostAddress.PUBLIC_HTTP_REFUSED, named.cleartextProblem { listOf(InetAddress.getByName("93.184.216.34")) })
        // One public address among private ones is still a refusal.
        assertNotNull(named.cleartextProblem { listOf(InetAddress.getByName("10.0.0.2"), InetAddress.getByName("93.184.216.34")) })
        // A name that does not resolve is not refused here: the request reports it as unreachable.
        assertNull(named.cleartextProblem { throw java.net.UnknownHostException(it) })
        // https and numbers never need a lookup.
        assertNull(ok(PcHostKind.OLLAMA, "https://pc.example.com").cleartextProblem { error("no lookup expected") })
        assertNull(ok(PcHostKind.OLLAMA, "192.168.1.20").cleartextProblem { error("no lookup expected") })
    }
}
