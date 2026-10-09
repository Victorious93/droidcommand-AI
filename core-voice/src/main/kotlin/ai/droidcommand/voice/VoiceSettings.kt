package ai.droidcommand.voice

/** What this build can actually do. Anything false is shown as unavailable, never faked. */
data class VoiceFeatures(
    /** `core-voice-neural-android` is linked into the app (sherpa-onnx TTS). */
    val neuralTts: Boolean = false,
    /** A wake-word detector (sherpa-onnx keyword spotting) is linked into the app. */
    val wakeWord: Boolean = false,
)

/** User choices for the voice features. Everything beyond the system voice is opt-in and OFF by default. */
data class VoiceSettings(
    val ttsEngine: TtsEngineChoice = TtsEngineChoice.SYSTEM,
    /** Id of the installed [VoiceModel] to speak with when [ttsEngine] is NEURAL. */
    val neuralVoiceId: String? = null,
    val wakeWord: WakeWordSettings = WakeWordSettings(),
    val approval: VoiceApprovalSettings = VoiceApprovalSettings(),
) {
    /**
     * The settings that may actually take effect in a build with [features]: an option the build cannot honour
     * is switched off rather than silently ignored, and approving by voice requires voice approvals on.
     */
    fun normalized(features: VoiceFeatures): VoiceSettings = copy(
        ttsEngine = if (features.neuralTts) ttsEngine else TtsEngineChoice.SYSTEM,
        wakeWord = if (features.wakeWord) wakeWord else WakeWordSettings(),
        approval = approval.copy(allowApproveByVoice = approval.enabled && approval.allowApproveByVoice),
    )
}

interface VoiceSettingsStore {
    fun load(): VoiceSettings

    fun save(settings: VoiceSettings)
}

class InMemoryVoiceSettingsStore(private var current: VoiceSettings = VoiceSettings()) : VoiceSettingsStore {
    override fun load() = current

    override fun save(settings: VoiceSettings) {
        current = settings
    }
}
