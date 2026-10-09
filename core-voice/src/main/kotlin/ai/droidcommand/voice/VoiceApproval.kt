package ai.droidcommand.voice

import ai.droidcommand.security.ApprovalProvider
import ai.droidcommand.security.ApprovalRequest
import ai.droidcommand.security.ApprovalResponse
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.RiskTier
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Owner choices; both default OFF. */
data class VoiceApprovalSettings(
    /** Voice may take part in approvals at all (it can then always say "deny"). */
    val enabled: Boolean = false,
    /** Voice may also APPROVE, and only [VoiceApprovalProvider.VOICE_APPROVABLE] tiers. */
    val allowApproveByVoice: Boolean = false,
)

/** Blocking speak/listen seam for approvals, called from a worker thread. */
interface VoiceApprovalIo {
    val available: Boolean

    /** Speaks [text] and returns true once it finished playing; false on failure or timeout. */
    fun speakAndWait(text: String, timeoutMs: Long): Boolean

    /** Listens for one utterance and returns its final transcript, or null (nothing heard / error / timeout). */
    fun listenOnce(timeoutMs: Long): String?

    /** Stops any speech or listening in progress; unblocks the two calls above. */
    fun cancel()
}

/** Strict phrase handling. Deny-biased: any deny word wins; approval needs an exact phrase; the rest is Unknown. */
object VoiceApprovalParser {
    enum class Intent { APPROVE, DENY, UNKNOWN }

    private val denyWords = setOf("no", "deny", "denied", "cancel", "stop", "reject", "decline", "dont", "don't", "nope", "abort")

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun denies(normalized: String) = normalized.split(' ').any { it in denyWords }

    fun intent(text: String): Intent {
        val n = normalize(text)
        return when {
            n.isEmpty() -> Intent.UNKNOWN
            denies(n) -> Intent.DENY
            n == "approve" || n == "i approve" -> Intent.APPROVE
            else -> Intent.UNKNOWN
        }
    }

    /** Second step: exactly "confirm <word>" approves; a deny word denies; anything else is Unknown. */
    fun confirmation(text: String, word: String): Intent {
        val n = normalize(text)
        return when {
            n.isEmpty() -> Intent.UNKNOWN
            denies(n) -> Intent.DENY
            n == "confirm ${word.lowercase()}" -> Intent.APPROVE
            else -> Intent.UNKNOWN
        }
    }
}

/**
 * Lets spoken answers take part in an [ApprovalRequest] without ever being able to grant authority by
 * themselves. The rules, all enforced here and covered by tests:
 *
 * - Voice only exists inside [requestApproval], i.e. for the one request currently pending; it never creates
 *   grants, never touches `GrantStore`, and cannot start an action. One voice session at a time; a request
 *   that arrives while another is in progress goes to [screen] only.
 * - Disabled by default. When enabled, voice can always DENY. It can APPROVE only if [VoiceApprovalSettings
 *   .allowApproveByVoice] is on AND the tier is in [VOICE_APPROVABLE] AND the operation description was short
 *   enough to be read back in full. Everything else (DESTRUCTIVE, IRREVERSIBLE, long descriptions) needs the
 *   on-screen answer: the [screen] provider runs concurrently and the first decisive answer wins.
 * - Approving takes two steps: say "approve", then repeat a random challenge word ("confirm <word>") that
 *   the device just read out. Recorded audio of a generic "yes" therefore cannot approve.
 * - No listening while the device is speaking ([VoiceApprovalIo.speakAndWait] returns first).
 * - Unrecognised speech never approves; after [MAX_ATTEMPTS] misses voice abstains and the screen decides.
 * - Every voice decision is written to [auditLog] tagged `[voice]` *before* it is returned; if the audit
 *   log refuses the event, the answer becomes Denied (fail closed).
 * - Whole exchange is bounded by [ApprovalRequest.timeoutMs]; no answer at all → [ApprovalResponse.TimedOut].
 *
 * Known limits (not fixable here): there is no speaker verification, so anyone in earshot who says the
 * challenge word after "approve" can approve a REVERSIBLE request; and [ApprovalProvider] has no cancel hook,
 * so when voice wins the race an on-screen dialog may stay visible until its own timeout (its late answer is
 * discarded).
 */
class VoiceApprovalProvider(
    private val screen: ApprovalProvider,
    private val io: VoiceApprovalIo,
    private val auditLog: AuditLog,
    private val settings: () -> VoiceApprovalSettings,
    private val challengeWords: List<String> = DEFAULT_WORDS,
    private val pickIndex: (Int) -> Int = { SecureRandom().nextInt(it) },
    /** Called around a voice session so the app can free the microphone (e.g. pause wake-word detection). */
    private val onSessionStart: () -> Unit = {},
    private val onSessionEnd: () -> Unit = {},
) : ApprovalProvider {
    private val busy = AtomicBoolean(false)

    init {
        require(challengeWords.isNotEmpty() && challengeWords.all { it.matches(Regex("[a-z]+")) }) { "challenge words must be lowercase letters" }
    }

    override fun requestApproval(request: ApprovalRequest): ApprovalResponse {
        val s = settings()
        if (!s.enabled || request.timeoutMs <= 0 || !io.available || !busy.compareAndSet(false, true)) {
            return screen.requestApproval(request)
        }
        try {
            return race(request, s)
        } finally {
            busy.set(false)
        }
    }

    private fun race(request: ApprovalRequest, s: VoiceApprovalSettings): ApprovalResponse {
        val decision = CompletableFuture<ApprovalResponse>()
        val cancelled = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.timeoutMs)

        daemon("approval-screen") {
            val r = try {
                screen.requestApproval(request)
            } catch (_: Throwable) {
                ApprovalResponse.Unavailable
            }
            // An unavailable screen must not end the race: voice may still be able to deny.
            if (r != ApprovalResponse.Unavailable) decision.complete(r)
        }
        val voice = daemon("approval-voice") {
            onSessionStart()
            try {
                val answer = runVoice(request, s, cancelled, deadline)
                if (answer != null && !cancelled.get() && !decision.isDone) {
                    val finalAnswer = recordVoice(request, answer)
                    decision.complete(finalAnswer)
                }
            } finally {
                onSessionEnd()
            }
        }

        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)
        val result = try {
            decision.get(remaining, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            ApprovalResponse.TimedOut
        } catch (_: ExecutionException) {
            ApprovalResponse.Unavailable
        }
        cancelled.set(true)
        io.cancel()
        voice.join(JOIN_MS)
        return result
    }

    /** Returns Approved/Denied, or null when voice abstains (unrecognised, not allowed, cancelled, timed out). */
    private fun runVoice(request: ApprovalRequest, s: VoiceApprovalSettings, cancelled: AtomicBoolean, deadline: Long): ApprovalResponse? {
        fun left() = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        fun live() = !cancelled.get() && left() > 0

        val description = request.operationDescription.replace(Regex("\\s+"), " ").trim()
        val fullyReadBack = description.length <= MAX_READBACK_CHARS && "```" !in description && "http" !in description.lowercase()
        val mayApprove = s.allowApproveByVoice && request.riskTier in VOICE_APPROVABLE && fullyReadBack
        val spoken = description.replace('_', ' ')
        val ask = if (mayApprove) {
            "Approval requested. $spoken. Risk: ${tierName(request.riskTier)}. Say approve, or say deny."
        } else {
            "Approval requested. $spoken. Risk: ${tierName(request.riskTier)}. Say deny to refuse. Approve on screen."
        }
        if (!io.speakAndWait(ask, left().coerceAtMost(SPEAK_MS))) return null

        var misses = 0
        while (live() && misses < MAX_ATTEMPTS) {
            val heard = io.listenOnce(left().coerceAtMost(LISTEN_MS))
            if (heard == null) {
                misses++
                continue
            }
            when (VoiceApprovalParser.intent(heard)) {
                VoiceApprovalParser.Intent.DENY -> return ApprovalResponse.Denied
                VoiceApprovalParser.Intent.UNKNOWN -> misses++
                VoiceApprovalParser.Intent.APPROVE -> {
                    if (!mayApprove) {
                        if (live()) io.speakAndWait("This needs approval on screen.", left().coerceAtMost(SPEAK_MS))
                        return null
                    }
                    return challenge(left = ::left, live = ::live)
                }
            }
        }
        return null
    }

    private fun challenge(left: () -> Long, live: () -> Boolean): ApprovalResponse? {
        val word = challengeWords[pickIndex(challengeWords.size).coerceIn(0, challengeWords.size - 1)]
        if (!live() || !io.speakAndWait("To confirm, say: confirm $word. Or say deny.", left().coerceAtMost(SPEAK_MS))) return null
        var misses = 0
        while (live() && misses < MAX_ATTEMPTS) {
            val heard = io.listenOnce(left().coerceAtMost(LISTEN_MS))
            if (heard == null) {
                misses++
                continue
            }
            when (VoiceApprovalParser.confirmation(heard, word)) {
                VoiceApprovalParser.Intent.DENY -> return ApprovalResponse.Denied
                VoiceApprovalParser.Intent.APPROVE -> return ApprovalResponse.Approved
                VoiceApprovalParser.Intent.UNKNOWN -> misses++
            }
        }
        if (live()) io.speakAndWait("Not confirmed.", left().coerceAtMost(SPEAK_MS))
        return null
    }

    /** Audits a voice decision first; if that cannot be recorded, an approval is downgraded to Denied. */
    private fun recordVoice(request: ApprovalRequest, answer: ApprovalResponse): ApprovalResponse {
        val type = if (answer == ApprovalResponse.Approved) AuditEventType.APPROVAL_APPROVED else AuditEventType.APPROVAL_DENIED
        val stored = auditLog.record(
            AuditEvent(
                type,
                "voice:tool:${request.toolId}",
                "[voice] approval request '${request.requestId}' (risk=${request.riskTier}) answered ${answer::class.simpleName} by voice",
            ),
        )
        return if (stored) answer else ApprovalResponse.Denied
    }

    private fun tierName(t: RiskTier) = t.name.lowercase().replace('_', ' ')

    private fun daemon(name: String, body: () -> Unit): Thread = Thread(body, name).apply {
        isDaemon = true
        start()
    }

    companion object {
        /** The only tiers voice may approve. DESTRUCTIVE and IRREVERSIBLE always need the on-screen answer. */
        val VOICE_APPROVABLE = setOf(RiskTier.READ_ONLY, RiskTier.REVERSIBLE)
        val DEFAULT_WORDS = listOf(
            "amber", "birch", "cobalt", "delta", "ember", "falcon", "garnet", "harbor",
            "indigo", "juniper", "kestrel", "lantern", "meadow", "nectar", "orchid", "quartz",
        )
        const val MAX_ATTEMPTS = 2
        const val MAX_READBACK_CHARS = 300
        private const val LISTEN_MS = 8_000L
        private const val SPEAK_MS = 30_000L
        private const val JOIN_MS = 1_000L
    }
}

/**
 * [VoiceApprovalIo] over raw engines, blocking on their callbacks. Use engine instances dedicated to approvals
 * (or ones the rest of the app is not using at that moment): this class stops the engines on [cancel].
 */
class EngineVoiceApprovalIo(private val stt: SpeechToText, private val tts: TextToSpeechEngine) : VoiceApprovalIo {
    override val available: Boolean get() = stt.available && tts.available

    override fun speakAndWait(text: String, timeoutMs: Long): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        try {
            tts.speak(SpeechText.clean(text)) {
                ok = it
                done.countDown()
            }
        } catch (_: Exception) {
            return false
        }
        val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) runCatching { tts.stop() }
        return finished && ok
    }

    override fun listenOnce(timeoutMs: Long): String? {
        val done = java.util.concurrent.CountDownLatch(1)
        var text: String? = null
        try {
            stt.start { event ->
                when (event) {
                    is SttEvent.Final -> {
                        text = event.text
                        done.countDown()
                    }
                    is SttEvent.Error -> done.countDown()
                    is SttEvent.Partial -> Unit
                }
            }
        } catch (_: Exception) {
            return null
        }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) runCatching { stt.stop() }
        return text
    }

    override fun cancel() {
        runCatching { tts.stop() }
        runCatching { stt.stop() }
    }
}
