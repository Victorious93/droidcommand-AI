package ai.droidcommand.app.approval

import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditLog
import ai.droidcommand.voice.VoiceApprovalIo
import ai.droidcommand.voice.VoiceApprovalSettings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceApprovalPromptTest {
    private class RecordingAudit : AuditLog {
        val events = CopyOnWriteArrayList<AuditEvent>()

        override fun record(event: AuditEvent): Boolean = events.add(event)
    }

    /** Scripted microphone: returns [heard] in order, then blocks (like real silence) until cancelled. */
    private class FakeIo(private val heard: List<String>, override val available: Boolean = true) : VoiceApprovalIo {
        val spoken = CopyOnWriteArrayList<String>()
        private val cancelled = CountDownLatch(1)
        private var next = 0

        override fun speakAndWait(text: String, timeoutMs: Long): Boolean {
            spoken += text
            return true
        }

        override fun listenOnce(timeoutMs: Long): String? {
            if (next < heard.size) return heard[next++]
            cancelled.await(minOf(timeoutMs, 2_000), TimeUnit.MILLISECONDS)
            return null
        }

        override fun cancel() = cancelled.countDown()
    }

    private val screen = ComposeApprovalPrompt()
    private val audit = RecordingAudit()
    private var held = 0
    private var released = 0

    private fun prompt(io: FakeIo, enabled: Boolean, approveByVoice: Boolean = true) = VoiceApprovalPrompt(
        screen = screen,
        io = { io },
        auditLog = audit,
        settings = { VoiceApprovalSettings(enabled, approveByVoice) },
        onMicrophoneNeeded = { held++ },
        onMicrophoneReleased = { released++ },
    )

    private fun ask(p: VoiceApprovalPrompt): Pair<Thread, AtomicReference<Boolean?>> {
        val out = AtomicReference<Boolean?>(null)
        val t = Thread { out.set(p.requestApproval("run_shell", mapOf("cmd" to "ls"), "needs confirmation")) }.apply { isDaemon = true; start() }
        return t to out
    }

    private fun awaitDialog(shown: Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((screen.pending.value != null) != shown && System.nanoTime() < end) Thread.sleep(5)
        assertEquals(shown, screen.pending.value != null)
    }

    @Test
    fun voice_approvals_off_is_exactly_the_old_screen_gate() {
        val io = FakeIo(listOf("approve"))
        val (t, r) = ask(prompt(io, enabled = false))
        awaitDialog(true)
        assertTrue(io.spoken.isEmpty(), "nothing is spoken when the feature is off")
        screen.resolve(true)
        t.join(5000)
        assertEquals(true, r.get())
        assertEquals(0, held)
        assertTrue(audit.events.isEmpty())
    }

    @Test
    fun saying_deny_refuses_clears_the_dialog_and_is_audited() {
        val io = FakeIo(listOf("deny"))
        val (t, r) = ask(prompt(io, enabled = true))
        t.join(10_000)
        assertEquals(false, r.get())
        awaitDialog(false)
        assertTrue(audit.events.single().detail.contains("[voice]"))
        assertEquals(1, held)
        assertEquals(1, released)
        assertTrue(io.spoken.first().contains("run shell"), "the tool is read aloud")
    }

    @Test
    fun voice_can_never_approve_even_when_allowed_and_followed_by_the_challenge() {
        // DESTRUCTIVE is not voice-approvable: "approve" is answered with "needs approval on screen".
        val io = FakeIo(listOf("approve", "confirm amber"))
        val (t, r) = ask(prompt(io, enabled = true, approveByVoice = true))
        Thread.sleep(300)
        assertNull(r.get(), "voice must not have decided")
        awaitDialog(true)
        assertTrue(io.spoken.any { "screen" in it })
        screen.resolve(true) // the on-screen Approve is the only way
        t.join(10_000)
        assertEquals(true, r.get())
        assertTrue(audit.events.none { it.detail.contains("Approved") && it.detail.contains("[voice]") })
    }

    @Test
    fun the_on_screen_deny_still_works_with_voice_on() {
        val (t, r) = ask(prompt(FakeIo(emptyList()), enabled = true))
        awaitDialog(true)
        screen.resolve(false)
        t.join(10_000)
        assertEquals(false, r.get())
    }

    @Test
    fun unavailable_microphone_falls_back_to_the_screen() {
        val io = FakeIo(emptyList(), available = false)
        val (t, r) = ask(prompt(io, enabled = true))
        awaitDialog(true)
        assertTrue(io.spoken.isEmpty())
        screen.resolve(true)
        t.join(5000)
        assertEquals(true, r.get())
    }
}
