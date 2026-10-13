package ai.droidcommand.cloudtunnel

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SharedCodingTunnelTest {
    @Test
    fun `one session walks plan then edit then report and names both slots`() {
        var tick = 0L
        val tunnel = SharedCodingTunnel(StubCodingTunnelTransport(), MemoryCodingSessionStore(), clock = { tick++ })
        val opened = tunnel.open("  Shared  ")
        assertEquals("Shared", opened.title)
        assertEquals(TunnelPhase.PLAN, opened.phase)
        assertEquals(TunnelBackendKind.STUB, opened.backend)
        assertTrue(tunnel.statusNote.contains("PLANNED"))

        val planned = tunnel.submit(opened.id, "Add a blue theme")
        assertEquals(TunnelPhase.EDIT, planned.phase)
        assertEquals(TunnelParticipant.USER, planned.events[0].participant)
        assertEquals(TunnelParticipant.CURSOR_CLOUD_AGENT, planned.events[1].participant)
        assertTrue(planned.events[1].body.contains("not a Cursor cloud agent"))

        val edited = tunnel.submit(opened.id, "Change Color.kt")
        assertEquals(TunnelPhase.REPORT, edited.phase)
        assertEquals(TunnelParticipant.CHATGPT_CODEX, edited.events.last().participant)
        assertTrue(edited.events.last().body.contains("No files were read or written"))

        val reported = tunnel.submit(opened.id, "Summarize")
        assertEquals(TunnelPhase.REPORT, reported.phase)
        assertTrue(reported.events.last().body.contains("No remote agent ran"))

        val closed = tunnel.close(opened.id)
        assertEquals(TunnelPhase.CLOSED, closed.phase)
        assertFailsWith<ClosedCodingSession> { tunnel.submit(opened.id, "again") }
    }

    @Test
    fun `sessions survive a new store over the same file and stay capped`() {
        val file = Files.createTempDirectory("tunnel").resolve("sessions.json")
        val first = SharedCodingTunnel(StubCodingTunnelTransport(), FileCodingSessionStore(file), ids = { "s1" }, clock = { 1L })
        first.open("One")
        val reopened = FileCodingSessionStore(file)
        assertEquals("One", reopened.get("s1")?.title)

        val clock = java.util.concurrent.atomic.AtomicLong(10)
        val filler = SharedCodingTunnel(
            StubCodingTunnelTransport(),
            FileCodingSessionStore(file),
            ids = { "s" + clock.get() },
            clock = { clock.getAndIncrement() },
        )
        repeat(FileCodingSessionStore.MAX_SESSIONS + 5) { filler.open("n$it") }
        assertEquals(FileCodingSessionStore.MAX_SESSIONS, FileCodingSessionStore(file).list().size)
    }
}
