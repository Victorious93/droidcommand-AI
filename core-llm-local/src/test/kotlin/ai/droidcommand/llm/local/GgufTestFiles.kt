package ai.droidcommand.llm.local

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A syntactically valid GGUF header followed by [payload]; not a loadable model, which no JVM test needs. */
internal fun ggufBytes(
    payload: ByteArray = "tensor data".toByteArray(),
    version: Int = 3,
    tensors: Long = 291,
    entries: Long = 24,
    magic: String = "GGUF",
): ByteArray {
    val header = ByteBuffer.allocate(GgufHeader.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    header.put(magic.toByteArray(Charsets.US_ASCII))
    header.putInt(version)
    header.putLong(tensors)
    header.putLong(entries)
    return header.array() + payload
}
