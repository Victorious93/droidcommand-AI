package ai.droidcommand.cloudsession

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InMemoryCloudSessionTunnelTest {
    private var clock = Instant.parse("2026-10-12T00:00:00Z")
    private var ids = 0

    private fun tunnel(transport: CloudSessionTransport = StubCloudSessionTransport()) = InMemoryCloudSessionTunnel(
        transport = transport,
        now = { clock.also { clock = clock.plusSeconds(1) } },
        newId = { "s${++ids}" },
    )

    @Test
    fun `open records a plan and advance walks edit then report then done`() {
        val tunnel = tunnel()
        val opened = tunnel.open("  Rename the theme  ")
        assertEquals("s1", opened.id)
        assertEquals("Rename the theme", opened.goal)
        assertEquals(CloudSessionPhase.PLAN, opened.phase)
        assertTrue(opened.events.single().body.contains("PLANNED stub plan"))

        val edited = tunnel.advance(opened.id)
        assertEquals(CloudSessionPhase.EDIT, edited.phase)
        assertTrue(edited.events.last().body.contains("PLANNED stub edit"))
        assertTrue(edited.events.last().body.contains("no files were changed"))

        val reported = tunnel.advance(opened.id)
        assertEquals(CloudSessionPhase.REPORT, reported.phase)
        assertTrue(reported.events.last().body.contains("PLANNED stub report"))

        val done = tunnel.advance(opened.id)
        assertEquals(CloudSessionPhase.DONE, done.phase)
        assertEquals(done, tunnel.advance(opened.id))
        assertEquals(listOf(CloudSessionPhase.PLAN, CloudSessionPhase.EDIT, CloudSessionPhase.REPORT, CloudSessionPhase.DONE), done.events.map { it.phase })
    }

    @Test
    fun `blank goal is rejected and cancel stops a live session`() {
        val tunnel = tunnel()
        assertFailsWith<IllegalArgumentException> { tunnel.open("   ") }
        val opened = tunnel.open("Investigate the build")
        val cancelled = tunnel.cancel(opened.id)
        assertEquals(CloudSessionPhase.CANCELLED, cancelled.phase)
        assertEquals(cancelled, tunnel.cancel(opened.id))
        assertEquals(cancelled, tunnel.advance(opened.id))
    }

    @Test
    fun `unknown id is rejected and list is newest first`() {
        val tunnel = tunnel()
        tunnel.open("first")
        tunnel.open("second")
        assertEquals(listOf("second", "first"), tunnel.list().map { it.goal })
        assertFailsWith<IllegalArgumentException> { tunnel.advance("missing") }
    }

    @Test
    fun `backend status stays planned even when a custom transport is injected`() {
        val tunnel = tunnel(transport = EchoTransport())
        val opened = tunnel.open("custom")
        assertTrue(tunnel.backendStatus.startsWith("PLANNED"))
        assertEquals("edit:plan:custom", tunnel.advance(opened.id).events.last().body)
    }

    private class EchoTransport : CloudSessionTransport {
        override fun plan(goal: String) = "plan:$goal"
        override fun edit(goal: String, plan: String) = "edit:$plan"
        override fun report(goal: String, plan: String, edit: String) = "report:$edit"
    }
}
