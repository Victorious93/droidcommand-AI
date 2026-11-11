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

/** Writes GGUF key/value entries the way llama.cpp does, for [ggufModelBytes]. */
internal class GgufKv {
    private val out = java.io.ByteArrayOutputStream()
    var count = 0L
        private set

    private fun le(n: Int, bytes: Long) = repeat(n) { out.write(((bytes ushr (8 * it)) and 0xFF).toInt()) }

    private fun str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        le(8, b.size.toLong())
        out.write(b)
    }

    private fun key(name: String, type: Int) {
        str(name)
        le(4, type.toLong())
        count++
    }

    fun string(name: String, value: String) = apply {
        key(name, 8)
        str(value)
    }

    fun u32(name: String, value: Long) = apply {
        key(name, 4)
        le(4, value)
    }

    fun u16(name: String, value: Int) = apply {
        key(name, 2)
        le(2, value.toLong())
    }

    fun stringArray(name: String, values: List<String>) = apply {
        key(name, 9)
        le(4, 8)
        le(8, values.size.toLong())
        values.forEach(::str)
    }

    fun i32Array(name: String, values: List<Int>) = apply {
        key(name, 9)
        le(4, 5)
        le(8, values.size.toLong())
        values.forEach { le(4, it.toLong()) }
    }

    /** A value whose declared length is [declaredBytes] but which carries no bytes: a truncated or hostile file. */
    fun stringWithDeclaredLength(name: String, declaredBytes: Long) = apply {
        key(name, 8)
        le(8, declaredBytes)
    }

    fun bytes(): ByteArray = out.toByteArray()
}

/**
 * A GGUF header and a real key/value section describing a small chat model, followed by [payload]. Not loadable by
 * llama.cpp (there are no tensors behind it); it is what the pure-Kotlin metadata reader sees.
 */
internal fun ggufModelBytes(
    architecture: String = "llama",
    contextLength: Long? = 4096,
    tokenizer: Boolean = true,
    chatTemplate: Boolean = true,
    payload: ByteArray = "tensor data".toByteArray(),
    extra: GgufKv.() -> Unit = {},
): ByteArray {
    val kv = GgufKv()
    kv.string("general.architecture", architecture)
    kv.string("general.name", "Test Model")
    contextLength?.let { kv.u32("$architecture.context_length", it) }
    kv.u32("$architecture.block_count", 16)
    kv.u32("$architecture.embedding_length", 2048)
    kv.u32("$architecture.attention.head_count", 32)
    kv.u32("$architecture.attention.head_count_kv", 8)
    if (tokenizer) {
        kv.string("tokenizer.ggml.model", "llama")
        kv.stringArray("tokenizer.ggml.tokens", listOf("<unk>", "<s>", "</s>", "hello", "wörld"))
        kv.i32Array("tokenizer.ggml.token_type", listOf(2, 3, 3, 1, 1))
    }
    if (chatTemplate) kv.string("tokenizer.chat_template", "{{ messages }}")
    kv.extra()
    return ggufBytes(payload = kv.bytes() + payload, entries = kv.count)
}
