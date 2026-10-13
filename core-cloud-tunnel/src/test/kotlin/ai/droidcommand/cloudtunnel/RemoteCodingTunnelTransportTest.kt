package ai.droidcommand.cloudtunnel

import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteCodingTunnelTransportTest {
    private val session = CodingSession(
        id = "s1",
        title = "Demo",
        phase = TunnelPhase.PLAN,
        events = emptyList(),
        backend = TunnelBackendKind.REMOTE_PLANNED,
        updatedAtEpochMs = 0,
    )

    @Test
    fun `a parsed remote turn is labeled unverified and advances only when the body names a phase`() {
        val transport = scripted(HttpResponseSpec(200, emptyMap(), """{"participant":"CHATGPT_CODEX","phase":"EDIT","body":"diff"}"""))
        val exchange = transport.exchange(session, "edit the theme")
        assertEquals(TunnelParticipant.CHATGPT_CODEX, exchange.participant)
        assertEquals(TunnelPhase.EDIT, exchange.phase)
        assertTrue(exchange.body.contains("did not verify"))
        assertTrue(exchange.body.contains("diff"))
        assertTrue(transport.statusNote.contains("PLANNED"))
    }

    @Test
    fun `a failed or unreadable response does not advance the phase`() {
        val failed = scripted(HttpResponseSpec(404, emptyMap(), "missing")).exchange(session, "go")
        assertEquals(TunnelPhase.PLAN, failed.phase)
        assertEquals(TunnelParticipant.REMOTE_UNVERIFIED, failed.participant)
        assertTrue(failed.body.contains("404"))

        val opaque = scripted(HttpResponseSpec(200, emptyMap(), "not-json")).exchange(session, "go")
        assertEquals(TunnelPhase.PLAN, opaque.phase)
        assertTrue(opaque.body.contains("does not treat"))
    }

    private fun scripted(response: HttpResponseSpec): RemoteCodingTunnelTransport {
        val http = object : HttpTransport {
            override fun send(request: HttpRequestSpec): HttpResponseSpec {
                assertTrue(request.url.endsWith("/v1/coding-sessions/s1/turns"))
                assertEquals("POST", request.method)
                assertTrue(request.body.orEmpty().contains("edit the theme") || request.body.orEmpty().contains("\"go\""))
                return response
            }
        }
        return RemoteCodingTunnelTransport(RemoteClient(RemoteEndpoint("https://tunnel.example.invalid"), http))
    }
}
