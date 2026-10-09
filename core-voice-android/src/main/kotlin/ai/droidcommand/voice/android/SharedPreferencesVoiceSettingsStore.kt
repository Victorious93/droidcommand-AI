package ai.droidcommand.voice.android

import ai.droidcommand.voice.TtsEngineChoice
import ai.droidcommand.voice.VoiceApprovalSettings
import ai.droidcommand.voice.VoiceSettings
import ai.droidcommand.voice.VoiceSettingsStore
import ai.droidcommand.voice.WakeWordSettings
import android.content.Context

/**
 * [VoiceSettingsStore] over plain SharedPreferences (none of this is secret). NEVER COMPILED OR RUN. Anything
 * missing or unrecognised reads as the OFF default, never as an enabled option.
 */
class SharedPreferencesVoiceSettingsStore(context: Context) : VoiceSettingsStore {
    private val prefs = context.applicationContext.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)

    override fun load(): VoiceSettings = VoiceSettings(
        ttsEngine = runCatching { TtsEngineChoice.valueOf(prefs.getString(KEY_ENGINE, null).orEmpty()) }.getOrDefault(TtsEngineChoice.SYSTEM),
        neuralVoiceId = prefs.getString(KEY_VOICE, null),
        wakeWord = WakeWordSettings(
            enabled = prefs.getBoolean(KEY_WAKE, false),
            listenWhenScreenOff = prefs.getBoolean(KEY_WAKE_SCREEN_OFF, false),
        ),
        approval = VoiceApprovalSettings(
            enabled = prefs.getBoolean(KEY_APPROVAL, false),
            allowApproveByVoice = prefs.getBoolean(KEY_APPROVE_BY_VOICE, false),
        ),
    )

    override fun save(settings: VoiceSettings) {
        prefs.edit()
            .putString(KEY_ENGINE, settings.ttsEngine.name)
            .putString(KEY_VOICE, settings.neuralVoiceId)
            .putBoolean(KEY_WAKE, settings.wakeWord.enabled)
            .putBoolean(KEY_WAKE_SCREEN_OFF, settings.wakeWord.listenWhenScreenOff)
            .putBoolean(KEY_APPROVAL, settings.approval.enabled)
            .putBoolean(KEY_APPROVE_BY_VOICE, settings.approval.allowApproveByVoice)
            .apply()
    }

    private companion object {
        const val KEY_ENGINE = "tts_engine"
        const val KEY_VOICE = "neural_voice_id"
        const val KEY_WAKE = "wake_word"
        const val KEY_WAKE_SCREEN_OFF = "wake_word_screen_off"
        const val KEY_APPROVAL = "voice_approval"
        const val KEY_APPROVE_BY_VOICE = "approve_by_voice"
    }
}
