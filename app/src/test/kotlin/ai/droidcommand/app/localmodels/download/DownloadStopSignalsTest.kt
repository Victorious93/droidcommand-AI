package ai.droidcommand.app.localmodels.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadStopSignalsTest {
    @Test
    fun `a discard request is taken once, by whoever deletes the file`() {
        val signals = DownloadStopSignals()
        assertFalse(signals.consumeDiscard("m"), "nothing requested yet")
        signals.requestDiscard("m")
        assertTrue(signals.consumeDiscard("m"))
        assertFalse(signals.consumeDiscard("m"), "already taken")
    }

    @Test
    fun `a Stop or a new Start forgets a pending discard, so an old Cancel never discards a later download`() {
        val signals = DownloadStopSignals()
        signals.requestDiscard("m")
        signals.clear("m")
        assertFalse(signals.consumeDiscard("m"))
    }

    @Test
    fun `requests are per model`() {
        val signals = DownloadStopSignals()
        signals.requestDiscard("a")
        assertFalse(signals.consumeDiscard("b"))
        assertTrue(signals.consumeDiscard("a"))
    }

    @Test
    fun `observers hear that a partial file changed`() = runBlocking {
        val signals = DownloadStopSignals()
        var heard: String? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { heard = withTimeout(2_000) { signals.changes.first() } }
        signals.notifyChanged("m")
        job.join()
        assertEquals("m", heard)
    }
}
