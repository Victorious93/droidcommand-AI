package ai.droidcommand.voice

import ai.droidcommand.security.ApprovalProvider
import ai.droidcommand.security.ApprovalRequest
import ai.droidcommand.security.ApprovalResponse
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.RiskTier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceApprovalTest {
    // ---- parser ----

    @Test
    fun parser_is_deny_biased_and_strict_about_approval() {
        val p = VoiceApprovalParser
        assertEquals(VoiceApprovalParser.Intent.APPROVE, p.intent("Approve."))
        assertEquals(VoiceApprovalParser.Intent.APPROVE, p.intent("I approve"))
        assertEquals(VoiceApprovalParser.Intent.DENY, p.intent("no way, approve it"))
        assertEquals(VoiceApprovalParser.Intent.DENY, p.intent("Deny"))
        for (loose in listOf("yes", "sure", "ok", "yeah go ahead", "approved", "please approve", "approve approve", "")) {
            assertEquals(VoiceApprovalParser.Intent.UNKNOWN, p.intent(loose), "'$loose' must not approve")
        }
        assertEquals(VoiceApprovalParser.Intent.APPROVE, p.confirmation("Confirm, BLUE!", "blue"))
        assertEquals(VoiceApprovalParser.Intent.UNKNOWN, p.confirmation("confirm red", "blue"))
        assertEquals(VoiceApprovalParser.Intent.UNKNOWN, p.confirmation("yes", "blue"))
        assertEquals(VoiceApprovalParser.Intent.DENY, p.confirmation("confirm blue no", "blue"))
    }

    // ---- provider ----

    /** Scripted voice IO; once the script is exhausted it hears nothing (after a short wait). */
    private class FakeIo(val heard: MutableList<String?> = mutableListOf(), override var available: Boolean = true) : VoiceApprovalIo {
        val spoken = java.util.Collections.synchronizedList(mutableListOf<String>())
        var speakOk = true
        var listens = 0
        val cancelled = CountDownLatch(1)

        override fun speakAndWait(text: String, timeoutMs: Long): Boolean {
            spoken += text
            return speakOk
        }

        override fun listenOnce(timeoutMs: Long): String? {
            listens++
            if (heard.isNotEmpty()) return heard.removeAt(0)
            cancelled.await(minOf(timeoutMs, 150), TimeUnit.MILLISECONDS)
            return null
        }

        override fun cancel() = cancelled.countDown()
    }

    /** On-screen stand-in: answers after [delayMs] (default: never, i.e. blocks until the test ends). */
    private class FakeScreen(val answer: ApprovalResponse? = null, val delayMs: Long = 0) : ApprovalProvider {
        var calls = 0

        override fun requestApproval(request: ApprovalRequest): ApprovalResponse {
            calls++
            if (answer == null) {
                Thread.sleep(request.timeoutMs + 500)
                return ApprovalResponse.TimedOut
            }
            Thread.sleep(delayMs)
            return answer
        }
    }

    private fun req(tier: RiskTier, timeout: Long = 1500, description: String = "restart the web service") = ApprovalRequest(
        requestId = "r1",
        operationDescription = description,
        riskTier = tier,
        targetType = ExecutionTargetType.values().first(),
        toolId = "shell",
        capabilityId = CapabilityId("cap"),
        timeoutMs = timeout,
    )

    private val on = VoiceApprovalSettings(enabled = true, allowApproveByVoice = true)

    private fun provider(
        screen: ApprovalProvider,
        io: VoiceApprovalIo,
        audit: AuditLog = InMemoryAuditLog(),
        settings: VoiceApprovalSettings = on,
    ) = VoiceApprovalProvider(screen, io, audit, { settings }, challengeWords = listOf("blue"), pickIndex = { 0 })

    @Test
    fun disabled_by_default_goes_straight_to_screen_without_touching_voice() {
        val io = FakeIo()
        val screen = FakeScreen(ApprovalResponse.Approved)
        val p = provider(screen, io, settings = VoiceApprovalSettings())
        assertEquals(ApprovalResponse.Approved, p.requestApproval(req(RiskTier.REVERSIBLE)))
        assertTrue(io.spoken.isEmpty())
        assertEquals(0, io.listens)
    }

    @Test
    fun voice_can_deny_any_tier_and_is_audited() {
        val audit = InMemoryAuditLog()
        val io = FakeIo(mutableListOf("deny"))
        val p = provider(FakeScreen(), io, audit)
        assertEquals(ApprovalResponse.Denied, p.requestApproval(req(RiskTier.IRREVERSIBLE)))
        val e = audit.all().single()
        assertEquals(AuditEventType.APPROVAL_DENIED, e.type)
        assertTrue(e.detail.startsWith("[voice]"))
    }

    @Test
    fun voice_cannot_approve_destructive_even_with_the_right_phrases() {
        val io = FakeIo(mutableListOf("approve", "confirm blue"))
        val screen = FakeScreen(ApprovalResponse.TimedOut, delayMs = 600)
        val audit = InMemoryAuditLog()
        val r = provider(screen, io, audit).requestApproval(req(RiskTier.DESTRUCTIVE))
        assertEquals(ApprovalResponse.TimedOut, r, "the on-screen answer decides")
        assertTrue(audit.all().isEmpty(), "voice made no decision")
        assertTrue(io.spoken.any { it.contains("on screen", ignoreCase = true) })
    }

    @Test
    fun reversible_approval_needs_approve_then_the_spoken_challenge_word() {
        val audit = InMemoryAuditLog()
        val io = FakeIo(mutableListOf("approve", "confirm blue"))
        val r = provider(FakeScreen(), io, audit).requestApproval(req(RiskTier.REVERSIBLE))
        assertEquals(ApprovalResponse.Approved, r)
        assertTrue(io.spoken.any { it.contains("confirm blue") })
        assertTrue(io.spoken.first().contains("restart the web service"), "exact operation is read back")
        assertEquals(AuditEventType.APPROVAL_APPROVED, audit.all().single().type)
    }

    @Test
    fun a_wrong_challenge_word_never_approves() {
        val io = FakeIo(mutableListOf("approve", "confirm red", "yes"))
        val r = provider(FakeScreen(ApprovalResponse.Denied, delayMs = 800), io).requestApproval(req(RiskTier.REVERSIBLE))
        assertEquals(ApprovalResponse.Denied, r, "voice abstained; the screen answered")
    }

    @Test
    fun unrecognised_speech_abstains_and_silence_times_out_to_default_deny() {
        val io = FakeIo(mutableListOf("yes", "sure"))
        val r = provider(FakeScreen(), io).requestApproval(req(RiskTier.REVERSIBLE, timeout = 700))
        assertEquals(ApprovalResponse.TimedOut, r)
    }

    @Test
    fun approve_by_voice_is_off_unless_opted_in() {
        val io = FakeIo(mutableListOf("approve", "confirm blue"))
        val screen = FakeScreen(ApprovalResponse.TimedOut, delayMs = 500)
        val r = provider(screen, io, settings = VoiceApprovalSettings(enabled = true, allowApproveByVoice = false))
            .requestApproval(req(RiskTier.REVERSIBLE))
        assertEquals(ApprovalResponse.TimedOut, r)
    }

    @Test
    fun a_description_too_long_to_read_back_cannot_be_approved_by_voice() {
        val long = "do something " + "very ".repeat(100)
        val io = FakeIo(mutableListOf("approve", "confirm blue"))
        val r = provider(FakeScreen(ApprovalResponse.Denied, delayMs = 500), io).requestApproval(req(RiskTier.REVERSIBLE, description = long))
        assertEquals(ApprovalResponse.Denied, r)
    }

    @Test
    fun an_audit_log_that_refuses_the_event_turns_approval_into_denial() {
        val full = object : AuditLog {
            override fun record(event: AuditEvent) = false
        }
        val io = FakeIo(mutableListOf("approve", "confirm blue"))
        assertEquals(ApprovalResponse.Denied, provider(FakeScreen(), io, full).requestApproval(req(RiskTier.REVERSIBLE)))
    }

    @Test
    fun the_screen_answer_wins_when_it_comes_first_and_stops_voice() {
        val io = FakeIo()
        val r = provider(FakeScreen(ApprovalResponse.Approved), io).requestApproval(req(RiskTier.DESTRUCTIVE))
        assertEquals(ApprovalResponse.Approved, r)
        assertTrue(io.cancelled.await(2, TimeUnit.SECONDS), "voice IO was cancelled")
    }

    @Test
    fun unavailable_voice_io_falls_back_to_screen() {
        val off = FakeIo(available = false)
        val r = provider(FakeScreen(ApprovalResponse.Approved), off).requestApproval(req(RiskTier.REVERSIBLE))
        assertEquals(ApprovalResponse.Approved, r)
        assertEquals(0, off.listens)
    }

    @Test
    fun a_second_request_during_a_voice_session_goes_to_screen_only() {
        val screen = object : ApprovalProvider {
            override fun requestApproval(request: ApprovalRequest): ApprovalResponse {
                if (request.requestId == "r2") return ApprovalResponse.Approved
                Thread.sleep(request.timeoutMs + 300)
                return ApprovalResponse.TimedOut
            }
        }
        val io = FakeIo()
        val p = provider(screen, io)
        val first = Thread { p.requestApproval(req(RiskTier.REVERSIBLE, timeout = 1200)) }.apply { start() }
        Thread.sleep(250) // first request now holds the voice session
        val listensBefore = io.listens
        val second = p.requestApproval(req(RiskTier.REVERSIBLE).copy(requestId = "r2"))
        assertEquals(ApprovalResponse.Approved, second)
        assertEquals(listensBefore, io.listens, "the second request never touched the voice session")
        first.join(4000)
    }

    @Test
    fun session_hooks_run_around_a_voice_session() {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val p = VoiceApprovalProvider(
            FakeScreen(ApprovalResponse.Approved), FakeIo(), InMemoryAuditLog(), { on },
            onSessionStart = { events += "start" }, onSessionEnd = { events += "end" },
        )
        p.requestApproval(req(RiskTier.DESTRUCTIVE))
        Thread.sleep(300)
        assertEquals(listOf("start", "end"), events.toList())
    }
}
