package ai.droidcommand.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class VoiceSettingsTest {
    @Test
    fun everything_beyond_the_system_voice_is_off_by_default() {
        val s = VoiceSettings()
        assertEquals(TtsEngineChoice.SYSTEM, s.ttsEngine)
        assertFalse(s.wakeWord.enabled)
        assertFalse(s.wakeWord.listenWhenScreenOff)
        assertFalse(s.approval.enabled)
        assertFalse(s.approval.allowApproveByVoice)
    }

    @Test
    fun options_the_build_cannot_honour_are_switched_off() {
        val asked = VoiceSettings(
            ttsEngine = TtsEngineChoice.NEURAL,
            neuralVoiceId = "v",
            wakeWord = WakeWordSettings(enabled = true, listenWhenScreenOff = true),
        )
        val none = asked.normalized(VoiceFeatures())
        assertEquals(TtsEngineChoice.SYSTEM, none.ttsEngine)
        assertEquals(WakeWordSettings(), none.wakeWord)
        assertEquals(asked, asked.normalized(VoiceFeatures(neuralTts = true, wakeWord = true)))
    }

    @Test
    fun approving_by_voice_requires_voice_approvals_to_be_on() {
        val s = VoiceSettings(approval = VoiceApprovalSettings(enabled = false, allowApproveByVoice = true))
        assertFalse(s.normalized(VoiceFeatures()).approval.allowApproveByVoice)
    }

    @Test
    fun in_memory_store_round_trips() {
        val store = InMemoryVoiceSettingsStore()
        val s = VoiceSettings(approval = VoiceApprovalSettings(enabled = true))
        store.save(s)
        assertEquals(s, store.load())
    }
}
