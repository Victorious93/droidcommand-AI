package ai.droidcommand.app.approval

import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.ApprovalProvider
import ai.droidcommand.security.ApprovalRequest
import ai.droidcommand.security.ApprovalResponse
import ai.droidcommand.security.AuditLog
import ai.droidcommand.security.CapabilityId
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.RiskApprovalPolicy
import ai.droidcommand.security.RiskTier
import ai.droidcommand.voice.EngineVoiceApprovalIo
import ai.droidcommand.voice.SpeechToText
import ai.droidcommand.voice.TextToSpeechEngine
import ai.droidcommand.voice.VoiceApprovalIo
import ai.droidcommand.voice.VoiceApprovalProvider
import ai.droidcommand.voice.VoiceApprovalSettings
import ai.droidcommand.voice.neural.WakeWordHost
import java.util.UUID

/**
 * The app's [ApprovalPrompt] once voice approvals exist. COMPILES; JVM-tested for the parts that do not need
 * a device; never run with a real microphone.
 *
 * With voice approvals OFF (the default) this delegates to [screen] unchanged: same dialog, same blocking
 * behaviour, no timeout, nothing spoken, nothing listened to. When ON it runs the request through core-voice's
 * [VoiceApprovalProvider] racing the on-screen dialog.
 *
 * **Voice can only refuse in this build (owner decision 2026-10-09).** Every request is classified
 * [RiskTier.DESTRUCTIVE]. [VoiceApprovalProvider.VOICE_APPROVABLE] holds only READ_ONLY and REVERSIBLE, so voice
 * reads the request aloud and can say "deny", but approving always needs the on-screen button, whatever
 * `allowApproveByVoice` says. The tier is a constant, not looked up from the tool, because every tool that
 * reaches an approval here is SENSITIVE or ROOT (NORMAL tools never ask), and an unknown tool must not be
 * easier to approve. Raising a tier later is a one-line, reviewable change and a security decision.
 *
 * Fail closed throughout: denied, timed out, unavailable, a voice-provider error → `false`. A voice decision is
 * audited before it is returned (a refused audit write turns approval into denial), and when voice decides
 * first the dialog is cancelled so it cannot linger or catch a later tap. Voice approvals are bounded by the
 * policy's DESTRUCTIVE timeout (120 s); the screen-only path keeps its previous no-timeout behaviour.
 */
class VoiceApprovalPrompt(
    private val screen: ComposeApprovalPrompt,
    private val io: () -> VoiceApprovalIo,
    private val auditLog: AuditLog,
    private val settings: () -> VoiceApprovalSettings,
    private val onMicrophoneNeeded: () -> Unit = { WakeWordHost.hold?.invoke() },
    private val onMicrophoneReleased: () -> Unit = { WakeWordHost.release?.invoke() },
) : ApprovalPrompt {
    override fun requestApproval(reason: String): Boolean = requestApproval("approval", emptyMap(), reason)

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
        if (!settings().enabled) return screen.requestApproval(reason)

        val handle = ApprovalHandle()
        val screenProvider = ApprovalProvider {
            if (screen.requestApproval(reason, handle)) ApprovalResponse.Approved else ApprovalResponse.Denied
        }
        val request = ApprovalRequest(
            requestId = UUID.randomUUID().toString(),
            operationDescription = describe(toolName, reason),
            riskTier = RiskTier.DESTRUCTIVE,
            targetType = ExecutionTargetType.ANDROID,
            toolId = toolName,
            capabilityId = CapabilityId("app.tool-approval"),
            timeoutMs = RiskApprovalPolicy.defaultTimeoutMs(RiskTier.DESTRUCTIVE),
        )
        val voiceIo = io()
        val provider = VoiceApprovalProvider(
            screen = screenProvider,
            io = voiceIo,
            auditLog = auditLog,
            settings = settings,
            onSessionStart = onMicrophoneNeeded,
            onSessionEnd = onMicrophoneReleased,
        )
        val response = try {
            provider.requestApproval(request)
        } catch (_: Exception) {
            ApprovalResponse.Unavailable
        } finally {
            handle.cancel() // no-op if the dialog already answered; otherwise removes it
        }
        return response == ApprovalResponse.Approved
    }

    private fun describe(toolName: String, reason: String): String =
        "Tool ${toolName.replace('_', ' ')}. ${reason.trim()}"

    companion object {
        /** [VoiceApprovalIo] over engines dedicated to approvals; they are stopped when an approval ends. */
        fun engineIo(stt: SpeechToText, tts: TextToSpeechEngine): VoiceApprovalIo = EngineVoiceApprovalIo(stt, tts)
    }
}
