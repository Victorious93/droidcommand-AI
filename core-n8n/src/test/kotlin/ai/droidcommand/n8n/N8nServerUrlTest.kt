package ai.droidcommand.n8n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class N8nServerUrlTest {
    @Test
    fun `https on the public internet is allowed without a warning`() {
        val url = ok("https://n8n.example.com:5678/")
        assertEquals("https://n8n.example.com:5678", url.base)
        assertTrue(url.https)
        assertNull(url.warning)
        assertEquals("https://n8n.example.com:5678/api/v1/workflows", url.api("api/v1/workflows"))
    }

    @Test
    fun `http on a LAN address is allowed with a warning`() {
        val url = ok("http://192.168.1.20:5678")
        assertEquals("http://192.168.1.20:5678", url.base)
        assertFalse(url.https)
        assertNotNull(url.warning)
        assertTrue(url.warning.contains("HTTP", ignoreCase = true))
    }

    @Test
    fun `http localhost and 10-dot and 172-16 are LAN`() {
        assertIs<N8nUrlParse.Ok>(N8nServerUrl.parse("http://127.0.0.1:5678"))
        assertIs<N8nUrlParse.Ok>(N8nServerUrl.parse("http://localhost:5678"))
        assertIs<N8nUrlParse.Ok>(N8nServerUrl.parse("http://10.0.0.8:5678"))
        assertIs<N8nUrlParse.Ok>(N8nServerUrl.parse("http://172.16.4.2:5678"))
        assertIs<N8nUrlParse.Ok>(N8nServerUrl.parse("http://pi.local"))
    }

    @Test
    fun `http on a public host is refused`() {
        val rejected = assertIs<N8nUrlParse.Rejected>(N8nServerUrl.parse("http://n8n.example.com"))
        assertTrue(rejected.reason.contains("https", ignoreCase = true))
    }

    @Test
    fun `cloud metadata and embedded credentials are refused`() {
        assertIs<N8nUrlParse.Rejected>(N8nServerUrl.parse("http://169.254.169.254/latest/meta-data"))
        assertIs<N8nUrlParse.Rejected>(N8nServerUrl.parse("https://user:secret@n8n.example.com"))
        assertIs<N8nUrlParse.Rejected>(N8nServerUrl.parse("ftp://192.168.1.2"))
        assertIs<N8nUrlParse.Rejected>(N8nServerUrl.parse(""))
    }

    @Test
    fun `webhook path is joined without a double slash`() {
        val url = ok("https://n8n.example.com/n8n")
        assertEquals("https://n8n.example.com/n8n/webhook/hello", url.webhook("/hello"))
    }

    @Test
    fun `172-15 is not treated as LAN`() {
        assertFalse(N8nServerUrl.isLanHost("172.15.0.1"))
        assertTrue(N8nServerUrl.isLanHost("172.16.0.1"))
        assertTrue(N8nServerUrl.isLanHost("172.31.255.1"))
        assertFalse(N8nServerUrl.isLanHost("172.32.0.1"))
        assertFalse(N8nServerUrl.isLanHost("8.8.8.8"))
    }

    private fun ok(raw: String): N8nServerUrl = (N8nServerUrl.parse(raw) as N8nUrlParse.Ok).url
}
