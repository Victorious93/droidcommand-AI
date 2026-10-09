package ai.droidcommand.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NeuralTextToSpeechTest {
    private class FakeSink : AudioSink {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())

        @Volatile var writeResult = true

        @Volatile var finishResult = true

        override fun open(sampleRate: Int) {
            events += "open:$sampleRate"
        }
        override fun write(samples: FloatArray): Boolean {
            events += "write:${samples.size}"
            return writeResult
        }
        override fun finish(): Boolean {
            events += "finish"
            return finishResult
        }
        override fun abort() {
            events += "abort"
        }
    }

    private class FakeSynth(
        override var available: Boolean = true,
        val chunks: List<FloatArray> = listOf(FloatArray(3), FloatArray(5)),
        val failBefore: Boolean = false,
        val failAfterFirst: Boolean = false,
        val gate: CountDownLatch? = null,
        val entered: CountDownLatch? = null,
    ) : NeuralSynthesizer {
        override val sampleRate = 22050
        var delivered = 0

        override fun synthesize(text: String, onChunk: (FloatArray) -> Boolean) {
            if (failBefore) error("model corrupt")
            for ((i, c) in chunks.withIndex()) {
                if (i == 1) {
                    // Signalled only once chunk 0 has been delivered, so a test's stop() cannot overtake it.
                    entered?.countDown()
                    gate?.await(5, TimeUnit.SECONDS)
                }
                if (!onChunk(c)) return
                delivered++
                if (failAfterFirst && i == 0) error("native error")
            }
        }
    }

    /** Collects onDone results from any thread; each speak must produce exactly one. */
    private class Results {
        val q = LinkedBlockingQueue<Boolean>()
        val cb: (Boolean) -> Unit = { q.add(it) }
        fun next(): Boolean = q.poll(5, TimeUnit.SECONDS) ?: error("onDone never called")
        fun assertNoMore() {
            Thread.sleep(50)
            assertTrue(q.isEmpty(), "onDone called more than once")
        }
    }

    private fun engine(synth: NeuralSynthesizer, sink: AudioSink) = NeuralTextToSpeech(synth, sink)

    @Test
    fun streams_every_chunk_in_order_then_reports_done() {
        val sink = FakeSink()
        val tts = engine(FakeSynth(), sink)
        val r = Results()
        tts.speak("hello", r.cb)
        assertTrue(r.next())
        r.assertNoMore()
        assertEquals(listOf("open:22050", "write:3", "write:5", "finish"), sink.events.filter { it != "abort" })
        tts.shutdown()
    }

    @Test
    fun unavailable_synthesizer_reports_false_without_opening_audio() {
        val sink = FakeSink()
        val tts = engine(FakeSynth(available = false), sink)
        val r = Results()
        tts.speak("hi", r.cb)
        assertFalse(r.next())
        assertFalse(tts.available)
        assertTrue(sink.events.none { it.startsWith("open") })
        tts.shutdown()
    }

    @Test
    fun stop_mid_synthesis_cancels_and_reports_false_once() {
        val sink = FakeSink()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val synth = FakeSynth(gate = gate, entered = entered)
        val tts = engine(synth, sink)
        val r = Results()
        tts.speak("long text", r.cb)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        tts.stop()
        gate.countDown() // synthesizer now tries to deliver chunk 2, which must be refused
        assertFalse(r.next())
        r.assertNoMore()
        assertEquals(1, synth.delivered, "second chunk must not be delivered after stop")
        assertTrue(sink.events.none { it == "finish" })
        assertTrue(tts.available, "a user stop is not a model failure")
        tts.shutdown()
    }

    @Test
    fun a_new_speak_replaces_the_previous_one() {
        val sink = FakeSink()
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val tts = engine(FakeSynth(gate = gate, entered = entered), sink)
        val first = Results()
        val second = Results()
        tts.speak("one", first.cb)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        tts.speak("two", second.cb)
        gate.countDown()
        assertFalse(first.next())
        assertTrue(second.next())
        first.assertNoMore()
        second.assertNoMore()
        tts.shutdown()
    }

    @Test
    fun failure_before_any_audio_disables_the_engine() {
        val tts = engine(FakeSynth(failBefore = true), FakeSink())
        val r = Results()
        tts.speak("hi", r.cb)
        assertFalse(r.next())
        assertFalse(tts.available)
        tts.shutdown()
    }

    @Test
    fun failure_after_audio_started_keeps_the_engine_available() {
        val sink = FakeSink()
        val tts = engine(FakeSynth(failAfterFirst = true), sink)
        val r = Results()
        tts.speak("hi", r.cb)
        assertFalse(r.next())
        assertTrue(tts.available)
        assertEquals("abort", sink.events.last(), "audio output must be released after a failure")
        tts.shutdown()
    }

    @Test
    fun a_sink_that_refuses_audio_stops_synthesis() {
        val sink = FakeSink().apply { writeResult = false }
        val synth = FakeSynth()
        val tts = engine(synth, sink)
        val r = Results()
        tts.speak("hi", r.cb)
        assertFalse(r.next())
        assertEquals(0, synth.delivered)
        tts.shutdown()
    }

    @Test
    fun an_aborted_playback_drain_reports_false() {
        val sink = FakeSink().apply { finishResult = false }
        val tts = engine(FakeSynth(), sink)
        val r = Results()
        tts.speak("hi", r.cb)
        assertFalse(r.next())
        tts.shutdown()
    }

    @Test
    fun speak_after_shutdown_reports_false() {
        val tts = engine(FakeSynth(), FakeSink())
        tts.shutdown()
        val r = Results()
        tts.speak("hi", r.cb)
        assertFalse(r.next())
    }
}
