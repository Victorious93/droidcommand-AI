package ai.droidcommand.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FallbackTextToSpeechTest {
    private class Fake(var up: Boolean = true, val throwOnSpeak: Boolean = false) : TextToSpeechEngine {
        val spoken = mutableListOf<String>()
        var stops = 0
        override val available get() = up

        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            if (throwOnSpeak) error("boom")
            spoken += text
            onDone(true)
        }

        override fun stop() { stops++ }
    }

    @Test
    fun uses_primary_when_available() {
        val p = Fake()
        val f = Fake()
        FallbackTextToSpeech(p, f).speak("a") {}
        assertEquals(listOf("a"), p.spoken)
        assertTrue(f.spoken.isEmpty())
    }

    @Test
    fun falls_back_when_primary_unavailable_including_after_it_goes_down() {
        val p = Fake()
        val f = Fake()
        val tts = FallbackTextToSpeech(p, f)
        tts.speak("a") {}
        p.up = false
        tts.speak("b") {}
        assertEquals(listOf("a"), p.spoken)
        assertEquals(listOf("b"), f.spoken)
    }

    @Test
    fun falls_back_when_primary_throws_synchronously() {
        val f = Fake()
        var done: Boolean? = null
        FallbackTextToSpeech(Fake(throwOnSpeak = true), f).speak("a") { done = it }
        assertEquals(listOf("a"), f.spoken)
        assertEquals(true, done)
    }

    @Test
    fun availability_is_either_engine_and_both_down_reports_false() {
        val p = Fake(up = false)
        val f = Fake()
        assertTrue(FallbackTextToSpeech(p, f).available)
        f.up = false
        assertFalse(FallbackTextToSpeech(p, f).available)
    }

    @Test
    fun stop_reaches_both_engines_even_if_one_throws() {
        val p = object : TextToSpeechEngine {
            override val available = true
            override fun speak(text: String, onDone: (Boolean) -> Unit) = Unit
            override fun stop() = error("boom")
        }
        val f = Fake()
        runCatching { FallbackTextToSpeech(p, f).stop() }
        assertEquals(1, f.stops)
    }

    @Test
    fun selector_returns_system_directly_and_wraps_neural() {
        val n = Fake()
        val s = Fake()
        assertSame(s, selectTts(TtsEngineChoice.SYSTEM, n, s))
        val chosen = selectTts(TtsEngineChoice.NEURAL, n, s)
        chosen.speak("x") {}
        assertEquals(listOf("x"), n.spoken)
    }
}
