package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RamFitTest {
    private val mb = 1024L * 1024L

    @Test
    fun `estimate is at least the file plus the runtime floor`() {
        val file = 100L * mb
        val estimated = RamFit.estimateBytes(file, contextTokens = 2048)
        assertTrue(estimated >= file + RamFit.RUNTIME_FLOOR_BYTES)
        assertTrue(estimated >= file + 2048L * RamFit.KV_BYTES_PER_TOKEN)
    }

    @Test
    fun `a small file on a 8 GB device with plenty free fits`() {
        val file = 386_404_992L
        val r = RamFit.assess(file, 2048, totalRamBytes = 8L * 1024 * mb, availableRamBytes = 5L * 1024 * mb)
        assertEquals(RamFitVerdict.FITS, r.verdict)
        assertEquals("fits", RamFit.label(r.verdict))
        assertTrue(r.reason.contains("MB"))
    }

    @Test
    fun `when the estimate exceeds free RAM the verdict is tight`() {
        val file = 1_000L * mb
        val r = RamFit.assess(file, 2048, totalRamBytes = 4L * 1024 * mb, availableRamBytes = 200L * mb)
        assertEquals(RamFitVerdict.TIGHT, r.verdict)
        assertEquals("tight", RamFit.label(r.verdict))
    }

    @Test
    fun `when the estimate exceeds total RAM the verdict is too big`() {
        val file = 6L * 1024 * mb
        val r = RamFit.assess(file, 4096, totalRamBytes = 2L * 1024 * mb, availableRamBytes = 500L * mb)
        assertEquals(RamFitVerdict.TOO_BIG, r.verdict)
        assertEquals("too big", RamFit.label(r.verdict))
        assertTrue(r.reason.contains("smaller quantization"))
    }

    @Test
    fun `unknown total RAM is reported tight rather than silently fits`() {
        val r = RamFit.assess(100L * mb, 2048, totalRamBytes = 0L, availableRamBytes = 0L)
        assertEquals(RamFitVerdict.TIGHT, r.verdict)
    }
}
