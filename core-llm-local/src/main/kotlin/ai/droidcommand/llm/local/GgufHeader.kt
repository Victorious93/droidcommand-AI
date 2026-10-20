package ai.droidcommand.llm.local

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The fixed-size start of a GGUF file: magic, format version, tensor count and metadata key/value count. */
data class GgufHeader(val version: Int, val tensorCount: Long, val metadataEntryCount: Long) {
    companion object {
        /** 4 magic bytes + uint32 version + uint64 tensor count + uint64 metadata entry count. */
        const val SIZE_BYTES = 24

        /** llama.cpp reads GGUF v2 and v3; v1 is rejected by it ("no longer supported"). */
        const val MIN_VERSION = 2
        const val MAX_VERSION = 3

        /** Real models have thousands of tensors; a count beyond this is a corrupt or hostile header, not a model. */
        const val MAX_COUNT = 1L shl 20

        private val MAGIC = "GGUF".toByteArray(Charsets.US_ASCII)

        /** Parses the first [SIZE_BYTES] of [bytes]. Pure: no I/O. */
        fun parse(bytes: ByteArray): GgufHeaderResult {
            if (bytes.size < SIZE_BYTES) return GgufHeaderResult.Invalid("File is shorter than a GGUF header (${bytes.size} bytes).")
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                return GgufHeaderResult.Invalid("Missing GGUF magic: this is not a GGUF model file.")
            }
            val buffer = ByteBuffer.wrap(bytes, MAGIC.size, SIZE_BYTES - MAGIC.size).order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.getInt()
            // llama.cpp's own test for a big-endian file: a little-endian reader sees the low 16 bits as zero.
            if (version and 0xFFFF == 0) return GgufHeaderResult.Invalid("Big-endian GGUF files are not supported on this device.")
            if (version !in MIN_VERSION..MAX_VERSION) {
                return GgufHeaderResult.Invalid("Unsupported GGUF version $version (supported: $MIN_VERSION to $MAX_VERSION).")
            }
            val tensors = buffer.getLong()
            val entries = buffer.getLong()
            // Counts are unsigned 64-bit on disk; a set top bit reads as negative here and is out of range either way.
            if (tensors !in 1..MAX_COUNT) return GgufHeaderResult.Invalid("Implausible GGUF tensor count ($tensors).")
            if (entries !in 1..MAX_COUNT) return GgufHeaderResult.Invalid("Implausible GGUF metadata entry count ($entries).")
            return GgufHeaderResult.Valid(GgufHeader(version, tensors, entries))
        }

        /** Reads and parses just the header of [file]. Throws [IOException] only when the file cannot be read. */
        fun read(file: File): GgufHeaderResult {
            val head = ByteArray(SIZE_BYTES)
            val n = file.inputStream().use { input ->
                var total = 0
                while (total < head.size) {
                    val r = input.read(head, total, head.size - total)
                    if (r < 0) break
                    total += r
                }
                total
            }
            return parse(head.copyOf(n))
        }
    }
}

sealed interface GgufHeaderResult {
    data class Valid(val header: GgufHeader) : GgufHeaderResult

    data class Invalid(val reason: String) : GgufHeaderResult
}
