package ai.droidcommand.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeWordTest {
    private class FakeSource(override var available: Boolean = true, val failStart: Boolean = false) : AudioFrameSource {
        override val sampleRate = 16000
        var onFrame: ((FloatArray) -> Unit)? = null
        var starts = 0
        var stops = 0

        override fun start(onFrame: (FloatArray) -> Unit) {
            if (failStart) error("mic busy")
            starts++
            this.onFrame = onFrame
        }

        override fun stop() {
            stops++
            onFrame = null
        }
        fun push(f: FloatArray = FloatArray(4)) = onFrame?.invoke(f)
    }

    private class FakeEngine(val script: MutableList<String?> = mutableListOf(), val failOn: Int = -1) : KeywordEngine {
        var calls = 0
        var resets = 0

        override fun accept(samples: FloatArray, sampleRate: Int): String? {
            if (calls++ == failOn) error("onnx error")
            return if (script.isEmpty()) null else script.removeAt(0)
        }

        override fun reset() {
            resets++
        }
    }

    private class Recorder : WakeWordListener {
        val detected = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun onDetected(keyword: String) {
            detected += keyword
        }
        override fun onError(message: String) {
            errors += message
        }
    }

    @Test
    fun detects_keyword_and_applies_cooldown() {
        var t = 0L
        val src = FakeSource()
        val det = AudioWakeWordDetector(src, FakeEngine(mutableListOf("hey", "hey", "hey")), cooldownMs = 1000, now = { t })
        val r = Recorder()
        det.start(r)
        src.push()
        t = 500
        src.push()
        t = 1500
        src.push()
        assertEquals(listOf("hey", "hey"), r.detected, "second detection inside the cooldown is dropped")
    }

    @Test
    fun blank_keyword_is_not_a_detection() {
        val src = FakeSource()
        val det = AudioWakeWordDetector(src, FakeEngine(mutableListOf("", "  ", null)))
        val r = Recorder()
        det.start(r)
        repeat(3) { src.push() }
        assertTrue(r.detected.isEmpty())
    }

    @Test
    fun stop_releases_mic_and_ignores_late_frames() {
        val src = FakeSource()
        val engine = FakeEngine(mutableListOf("hey"))
        val det = AudioWakeWordDetector(src, engine)
        val r = Recorder()
        det.start(r)
        val late = src.onFrame!!
        det.stop()
        late(FloatArray(4))
        assertEquals(1, src.stops)
        assertTrue(r.detected.isEmpty())
        assertEquals(0, engine.calls)
    }

    @Test
    fun mic_failure_and_engine_failure_report_error_and_stop() {
        val r1 = Recorder()
        AudioWakeWordDetector(FakeSource(failStart = true), FakeEngine()).start(r1)
        assertEquals(1, r1.errors.size)

        val src = FakeSource()
        val det = AudioWakeWordDetector(src, FakeEngine(failOn = 0))
        val r2 = Recorder()
        det.start(r2)
        src.push()
        assertEquals(1, r2.errors.size)
        assertEquals(1, src.stops, "capture stopped after engine failure")
    }

    // ---- controller ----

    private class FakeStt : SpeechToText {
        override val available = true
        var listener: ((SttEvent) -> Unit)? = null
        override fun start(listener: (SttEvent) -> Unit) {
            this.listener = listener
        }
        override fun stop() = Unit
    }

    private class FakeTts : TextToSpeechEngine {
        override val available = true
        override fun speak(text: String, onDone: (Boolean) -> Unit) = Unit
        override fun stop() = Unit
    }

    private class Rig(mic: Boolean = true, detectorAvailable: Boolean = true) {
        val src = FakeSource(available = detectorAvailable)
        val detector = AudioWakeWordDetector(src, FakeEngine(mutableListOf("hey", "hey", "hey")), cooldownMs = 0)
        val stt = FakeStt()
        val transcripts = mutableListOf<String>()
        val states = mutableListOf<WakeWordState>()
        lateinit var wake: WakeWordController
        var micOk = mic
        val voice: VoiceController = VoiceController(stt, FakeTts()) { wake.onVoiceState(it) }

        init {
            wake = WakeWordController(detector, voice, { micOk }, { transcripts += it }) { states += it }
        }
    }

    @Test
    fun disabled_by_default_and_requires_permission_to_enable() {
        val rig = Rig(mic = false)
        assertFalse(WakeWordSettings().enabled)
        rig.wake.setEnabled(true)
        assertEquals(0, rig.src.starts)
        assertFalse(rig.states.last().enabled)
        assertTrue(rig.states.last().error!!.contains("permission"))
    }

    @Test
    fun detection_only_starts_dictation_then_resumes_after_the_transcript() {
        val rig = Rig()
        rig.wake.setEnabled(true)
        assertTrue(rig.states.last().listeningForWakeWord)
        rig.src.push() // "hey"
        assertEquals(1, rig.src.stops, "wake mic released for dictation")
        assertTrue(rig.voice.snapshot().listening)
        assertFalse(rig.states.last().listeningForWakeWord)
        rig.stt.listener!!(SttEvent.Final("open settings"))
        assertEquals(listOf("open settings"), rig.transcripts)
        assertEquals(2, rig.src.starts, "wake detection resumed")
        assertTrue(rig.states.last().listeningForWakeWord)
    }

    @Test
    fun dictation_that_cannot_start_resumes_detection() {
        val rig = Rig()
        rig.wake.setEnabled(true)
        rig.micOk = false // permission revoked after enabling
        rig.src.push()
        assertEquals(2, rig.src.starts)
        assertTrue(rig.states.last().listeningForWakeWord)
    }

    @Test
    fun disabling_stops_the_microphone() {
        val rig = Rig()
        rig.wake.setEnabled(true)
        rig.wake.setEnabled(false)
        assertEquals(1, rig.src.stops)
        assertEquals(WakeWordState(), rig.states.last())
    }

    @Test
    fun unavailable_detector_cannot_be_enabled() {
        val rig = Rig(detectorAvailable = false)
        rig.wake.setEnabled(true)
        assertFalse(rig.states.last().enabled)
        assertEquals(0, rig.src.starts)
    }

    @Test
    fun hold_frees_the_mic_and_release_resumes_only_when_all_holds_are_gone() {
        val rig = Rig()
        rig.wake.setEnabled(true)
        rig.wake.hold()
        rig.wake.hold()
        assertEquals(1, rig.src.stops)
        assertFalse(rig.states.last().listeningForWakeWord)
        rig.wake.release()
        assertEquals(1, rig.src.starts, "still held by the second caller")
        rig.wake.release()
        assertEquals(2, rig.src.starts)
        assertTrue(rig.states.last().listeningForWakeWord)
    }

    @Test
    fun a_hold_taken_during_dictation_keeps_detection_off_afterwards() {
        val rig = Rig()
        rig.wake.setEnabled(true)
        rig.src.push() // detection -> dictation
        rig.wake.hold()
        rig.stt.listener!!(SttEvent.Final("hello"))
        assertEquals(1, rig.src.starts, "not resumed while held")
        rig.wake.release()
        assertEquals(2, rig.src.starts)
    }
}
