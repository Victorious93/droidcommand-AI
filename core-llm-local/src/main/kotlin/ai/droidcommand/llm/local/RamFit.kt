package ai.droidcommand.llm.local

/** How an on-device GGUF is expected to sit in RAM, relative to what the device reports. */
enum class RamFitVerdict {
    /** Estimated working set is well under both total and currently available RAM. */
    FITS,

    /** It may load, but available RAM is tight or the estimate uses most of the device. */
    TIGHT,

    /** Estimated working set exceeds total RAM (or leaves too little for the OS). Do not load. */
    TOO_BIG,
}

data class RamFitAssessment(
    val estimatedBytes: Long,
    val verdict: RamFitVerdict,
    /** Safe to show: sizes in MB, never a path or token. */
    val reason: String,
)

/**
 * Rough RAM needed to *run* a GGUF, not just to store it.
 *
 * Weights ≈ the file (it is already quantized). On top of that: a runtime floor, a small fraction
 * of the file for scratch buffers, and a per-token KV-cache floor. Architecture is not parsed, so
 * the estimate errs high: a TOO_BIG verdict is safer than an uncatchable native OOM. Not a
 * substitute for llama.cpp's own allocator.
 */
object RamFit {
    internal const val RUNTIME_FLOOR_BYTES = 256L * 1024L * 1024L
    internal const val KV_BYTES_PER_TOKEN = 512L
    private const val MEGABYTE = 1024L * 1024L

    /** Bytes the process is likely to need once the model is loaded at [contextTokens]. */
    fun estimateBytes(fileBytes: Long, contextTokens: Int): Long {
        val file = fileBytes.coerceAtLeast(0L)
        val scratch = file / 20L // 5 %
        val kv = contextTokens.coerceAtLeast(0).toLong() * KV_BYTES_PER_TOKEN
        return file + maxOf(RUNTIME_FLOOR_BYTES, scratch) + kv
    }

    fun assess(
        fileBytes: Long,
        contextTokens: Int,
        totalRamBytes: Long,
        availableRamBytes: Long,
    ): RamFitAssessment {
        val estimated = estimateBytes(fileBytes, contextTokens)
        val total = totalRamBytes.coerceAtLeast(0L)
        val available = availableRamBytes.coerceAtLeast(0L)
        val mb = { n: Long -> (n + MEGABYTE - 1) / MEGABYTE }
        val verdict = when {
            total <= 0L -> RamFitVerdict.TIGHT
            estimated > total * 9 / 10 -> RamFitVerdict.TOO_BIG
            estimated > available -> RamFitVerdict.TIGHT
            estimated > total * 11 / 20 -> RamFitVerdict.TIGHT
            else -> RamFitVerdict.FITS
        }
        val reason = when (verdict) {
            RamFitVerdict.TOO_BIG ->
                "This model needs about ${mb(estimated)} MB of RAM to run, more than this device's ${mb(total)} MB. Pick a smaller quantization."
            RamFitVerdict.TIGHT ->
                "This model needs about ${mb(estimated)} MB of RAM (${mb(available)} MB free of ${mb(total)} MB). It may load, or the app may be killed."
            RamFitVerdict.FITS ->
                "About ${mb(estimated)} MB of RAM to run; ${mb(available)} MB free of ${mb(total)} MB."
        }
        return RamFitAssessment(estimated, verdict, reason)
    }

    fun label(verdict: RamFitVerdict): String = when (verdict) {
        RamFitVerdict.FITS -> "fits"
        RamFitVerdict.TIGHT -> "tight"
        RamFitVerdict.TOO_BIG -> "too big"
    }
}

/** Snapshot of device RAM. Android fills this from `ActivityManager.MemoryInfo`; tests inject numbers. */
fun interface DeviceMemory {
    fun snapshot(): MemorySnapshot
}

data class MemorySnapshot(val totalBytes: Long, val availableBytes: Long)
