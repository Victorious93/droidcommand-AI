package ai.droidcommand.app.localmodels

import java.io.ByteArrayOutputStream

/**
 * Bytes of a GGUF v3 file with a real header and key/value section describing a small chat model, then [payload].
 * llama.cpp could not load it (no tensors follow); it is exactly what the importer's own pure-Kotlin checks read.
 */
internal fun ggufFixture(
    architecture: String = "llama",
    contextLength: Int? = 4096,
    chatTemplate: Boolean = true,
    version: Int = 3,
    splitCount: Int? = null,
    type: String? = null,
    payload: ByteArray = "model-payload".toByteArray(),
): ByteArray {
    val kv = ByteArrayOutputStream()
    var entries = 0L

    fun le(n: Int, value: Long) = repeat(n) { kv.write(((value ushr (8 * it)) and 0xFF).toInt()) }

    fun str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        le(8, b.size.toLong())
        kv.write(b)
    }

    fun string(key: String, value: String) {
        str(key)
        le(4, 8)
        str(value)
        entries++
    }

    fun u32(key: String, value: Int) {
        str(key)
        le(4, 4)
        le(4, value.toLong())
        entries++
    }

    string("general.architecture", architecture)
    type?.let { string("general.type", it) }
    contextLength?.let { u32("$architecture.context_length", it) }
    splitCount?.let { u32("split.count", it) }
    string("tokenizer.ggml.model", "llama")
    // The vocabulary: an array of strings the reader has to step over, as in every real model.
    str("tokenizer.ggml.tokens")
    le(4, 9)
    le(4, 8)
    le(8, 3)
    listOf("<s>", "</s>", "hello").forEach(::str)
    entries++
    if (chatTemplate) string("tokenizer.chat_template", "{{ messages }}")

    val out = ByteArrayOutputStream()
    out.write("GGUF".toByteArray(Charsets.US_ASCII))
    repeat(4) { out.write((version ushr (8 * it)) and 0xFF) }
    repeat(8) { out.write(((291L ushr (8 * it)) and 0xFF).toInt()) }
    repeat(8) { out.write(((entries ushr (8 * it)) and 0xFF).toInt()) }
    out.write(kv.toByteArray())
    out.write(payload)
    return out.toByteArray()
}
