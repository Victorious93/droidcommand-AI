package ai.droidcommand.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceControllerTest {
    private class FakeStt(override val available: Boolean = true, val throwOnStart: Boolean = false) : SpeechToText {
        var listener: ((SttEvent) -> Unit)? = null
        var starts = 0

        override fun start(listener: (SttEvent) -> Unit) {
            starts++
            if (throwOnStart) error("recognizer busy")
            this.listener = listener
        }

        override fun stop() = Unit
    }

    private class FakeTts(override val available: Boolean = true, val throwOnSpeak: Boolean = false) : TextToSpeechEngine {
        val spoken = mutableListOf<String>()
        val callbacks = mutableListOf<(Boolean) -> Unit>()
        var stops = 0

        override fun speak(text: String, onDone: (Boolean) -> Unit) {
            if (throwOnSpeak) error("tts dead")
            spoken += text
            callbacks += onDone
        }

        override fun stop() {
            stops++
        }
    }

    private fun controller(stt: SpeechToText = FakeStt(), tts: TextToSpeechEngine = FakeTts()): Triple<VoiceController, () -> VoiceState, Unit> {
        var last = VoiceState()
        return Triple(VoiceController(stt, tts) { last = it }, { last }, Unit)
    }

    @Test
    fun `a final transcript is delivered once and listening ends`() {
        val stt = FakeStt()
        val (c, state, _) = controller(stt)
        val got = mutableListOf<String>()
        c.startListening(true) { got += it }
        assertTrue(state().listening)
        stt.listener!!(SttEvent.Partial("hel"))
        assertEquals("hel", state().partial)
        stt.listener!!(SttEvent.Final("  hello there "))
        assertEquals(listOf("hello there"), got)
        assertFalse(state().listening)
        assertEquals("", state().partial)
    }

    @Test
    fun `permission denial degrades to a message and never starts the recognizer`() {
        val stt = FakeStt()
        val (c, state, _) = controller(stt)
        c.startListening(false) { error("no transcript expected") }
        assertEquals(0, stt.starts)
        assertTrue(state().error!!.contains("permission"))
        assertFalse(state().listening)
    }

    @Test
    fun `unavailable recognizer and a throwing recognizer both degrade without throwing`() {
        val (c1, s1, _) = controller(stt = NullSpeechToText())
        c1.startListening(true) {}
        assertTrue(s1().error!!.contains("not available"))

        val (c2, s2, _) = controller(stt = FakeStt(throwOnStart = true))
        c2.startListening(true) {}
        assertTrue(s2().error!!.contains("recognizer busy"))
        assertFalse(s2().listening)
    }

    @Test
    fun `recognizer errors and blank transcripts produce no chat text`() {
        val stt = FakeStt()
        val (c, state, _) = controller(stt)
        val got = mutableListOf<String>()
        c.startListening(true) { got += it }
        stt.listener!!(SttEvent.Error("No speech heard."))
        assertEquals("No speech heard.", state().error)
        c.startListening(true) { got += it }
        stt.listener!!(SttEvent.Final("   "))
        assertTrue(got.isEmpty())
        assertFalse(state().listening)
    }

    @Test
    fun `a second start while listening is ignored and late events from an old session are dropped`() {
        val stt = FakeStt()
        val (c, state, _) = controller(stt)
        val got = mutableListOf<String>()
        c.startListening(true) { got += "a:$it" }
        c.startListening(true) { got += "b:$it" }
        assertEquals(1, stt.starts)
        val first = stt.listener!!
        first(SttEvent.Final("one"))
        c.startListening(true) { got += "c:$it" }
        first(SttEvent.Final("stale")) // from session 1, session 2 is active
        assertEquals(listOf("a:one"), got)
        assertTrue(state().listening)
    }

    @Test
    fun `auto mode speaks replies, off and tap do not`() {
        val tts = FakeTts()
        val (c, _, _) = controller(tts = tts)
        c.onReply("hi", SpeakMode.OFF)
        c.onReply("hi", SpeakMode.TAP)
        assertTrue(tts.spoken.isEmpty())
        c.onReply("hi", SpeakMode.AUTO)
        assertEquals(listOf("hi"), tts.spoken)
    }

    @Test
    fun `speaking state follows completion and a stale completion cannot clear a newer utterance`() {
        val tts = FakeTts()
        val (c, state, _) = controller(tts = tts)
        c.speak("first")
        c.speak("second")
        assertTrue(state().speaking)
        tts.callbacks[0](false) // old utterance reports stopped after being replaced
        assertTrue(state().speaking)
        assertNull(state().error)
        tts.callbacks[1](true)
        assertFalse(state().speaking)
    }

    @Test
    fun `unavailable or throwing tts degrades and listening stops speech first`() {
        val (c1, s1, _) = controller(tts = NullTextToSpeech())
        c1.speak("x")
        assertTrue(s1().error!!.contains("not available"))

        val (c2, s2, _) = controller(tts = FakeTts(throwOnSpeak = true))
        c2.speak("x")
        assertTrue(s2().error!!.contains("tts dead"))
        assertFalse(s2().speaking)

        val tts = FakeTts()
        val (c3, _, _) = controller(tts = tts)
        val before = tts.stops
        c3.startListening(true) {}
        assertTrue(tts.stops > before)
    }

    @Test
    fun `speech text drops code, urls and markdown and is capped`() {
        val cleaned = SpeechText.clean("## Title\nUse **bold** and [docs](https://x.y/z) or https://a.b/c\n```kotlin\nval x = 1\n```\ndone")
        assertEquals("Title Use bold and docs or link code omitted. done", cleaned)
        val long = SpeechText.clean("word ".repeat(2000))
        assertTrue(long.length <= SpeechText.MAX_CHARS)
    }
}
