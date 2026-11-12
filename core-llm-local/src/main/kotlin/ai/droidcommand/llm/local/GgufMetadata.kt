package ai.droidcommand.llm.local

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * What a GGUF file says about itself in its key/value section, read without loading the model. Only the entries the
 * app decides on are kept; everything else (the tokenizer's vocabulary above all) is skipped, not stored.
 *
 * Every value comes from the file, so none of it is trusted: it picks a message and a size estimate, nothing else.
 * llama.cpp still parses the file itself when it loads.
 */
data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    /** `general.architecture`, e.g. `llama`, `qwen2`, `gemma3`, `bert`, `clip`. */
    val architecture: String?,
    /** `general.type`: `model`, `adapter` (LoRA) or `mmproj` (vision projector). Absent in older files. */
    val type: String?,
    val name: String?,
    /** `<architecture>.context_length`: the window the model was trained with. */
    val contextLength: Int?,
    val blockCount: Int?,
    val embeddingLength: Int?,
    val headCount: Int?,
    val headCountKv: Int?,
    /** `split.count`: more than 1 means this file is one shard of a model split across several files. */
    val splitCount: Int?,
    val hasTokenizer: Boolean,
    val hasChatTemplate: Boolean,
    /**
     * Whether `tokenizer.chat_template` renders `<tool_call>` blocks for offered tools ([LocalToolProtocol]). Only
     * this yes/no is kept, not the template. False when there is no template or it is too long to have been read.
     */
    val chatTemplateDeclaresToolCalls: Boolean = false,
) {
    /**
     * Bytes of key/value cache one context token costs, from the model's own shape: per layer one key and one value
     * vector of `embedding / heads * kvHeads` 16-bit floats (llama.cpp's default cache type). Null when the file does
     * not state the shape (or states it per layer).
     */
    fun kvBytesPerToken(): Long? {
        val layers = blockCount ?: return null
        val embedding = embeddingLength ?: return null
        val heads = headCount?.takeIf { it > 0 } ?: return null
        val kvHeads = headCountKv?.takeIf { it > 0 } ?: heads
        if (layers <= 0 || embedding <= 0) return null
        return layers.toLong() * 2L * (embedding.toLong() / heads * kvHeads) * BYTES_PER_F16
    }

    private companion object {
        const val BYTES_PER_F16 = 2L
    }
}

sealed interface GgufMetadataResult {
    data class Valid(val metadata: GgufMetadata) : GgufMetadataResult

    /** [reason] is safe to show: it never contains file content. */
    data class Invalid(val reason: String) : GgufMetadataResult
}

/**
 * Pure-Kotlin reader for the GGUF header and key/value section (GGUF v2 and v3, little-endian). Bounded: it stops
 * with [GgufMetadataResult.Invalid] rather than follow a length that cannot be real, and never allocates more than
 * [MAX_KEPT_STRING_BYTES] for one value.
 */
object GgufMetadataReader {
    /** A real key/value section is a few megabytes (the vocabulary). Past this the file is corrupt or hostile. */
    const val MAX_SECTION_BYTES = 256L * 1024L * 1024L
    private const val MAX_KEY_BYTES = 1024
    private const val MAX_KEPT_STRING_BYTES = 256 * 1024

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    /** Throws [IOException] only when the file cannot be opened or read; a malformed file is [GgufMetadataResult.Invalid]. */
    fun read(file: File): GgufMetadataResult = file.inputStream().use { read(it) }

    fun read(input: InputStream): GgufMetadataResult {
        val source = Source(if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024))
        val head = ByteArray(GgufHeader.SIZE_BYTES)
        val n = source.readUpTo(head)
        val header = when (val parsed = GgufHeader.parse(head.copyOf(n))) {
            is GgufHeaderResult.Invalid -> return GgufMetadataResult.Invalid(parsed.reason)
            is GgufHeaderResult.Valid -> parsed.header
        }
        val strings = HashMap<String, String>()
        val numbers = HashMap<String, Long>()
        var tokenizer = false
        var template = false
        var toolCalls = false
        try {
            repeat(header.metadataEntryCount.toInt()) {
                val key = source.string(MAX_KEY_BYTES) ?: return GgufMetadataResult.Invalid("The GGUF metadata is damaged (a key is too long).")
                val type = source.u32().toInt()
                when {
                    key == "tokenizer.ggml.model" -> tokenizer = true
                    key == "tokenizer.chat_template" -> template = true
                }
                when (type) {
                    TYPE_STRING -> source.string(MAX_KEPT_STRING_BYTES)?.let {
                        if (wanted(key)) strings[key] = it
                        if (key == "tokenizer.chat_template") toolCalls = LocalToolProtocol.templateDeclaresToolCalls(it)
                    }
                    TYPE_ARRAY -> skipArray(source) ?: return GgufMetadataResult.Invalid("The GGUF metadata is damaged (unknown array type).")
                    else -> {
                        val value = scalar(source, type) ?: return GgufMetadataResult.Invalid("The GGUF metadata is damaged (unknown value type $type).")
                        if (wanted(key)) numbers[key] = value
                    }
                }
            }
        } catch (_: EOFException) {
            return GgufMetadataResult.Invalid("The file ends inside its GGUF metadata: it is incomplete. Copy it again.")
        } catch (_: SectionTooLarge) {
            return GgufMetadataResult.Invalid("The GGUF metadata is implausibly large: the file is damaged.")
        }
        val arch = strings["general.architecture"]
        fun int(suffix: String): Int? = arch?.let { numbers["$it.$suffix"] }?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
        return GgufMetadataResult.Valid(
            GgufMetadata(
                version = header.version,
                tensorCount = header.tensorCount,
                architecture = arch,
                type = strings["general.type"],
                name = strings["general.name"],
                contextLength = int("context_length"),
                blockCount = int("block_count"),
                embeddingLength = int("embedding_length"),
                headCount = int("attention.head_count"),
                headCountKv = int("attention.head_count_kv"),
                splitCount = numbers["split.count"]?.takeIf { it in 0..Int.MAX_VALUE }?.toInt(),
                hasTokenizer = tokenizer,
                hasChatTemplate = template,
                chatTemplateDeclaresToolCalls = toolCalls,
            ),
        )
    }

    private fun wanted(key: String): Boolean =
        key.startsWith("general.") || key == "split.count" ||
            key.endsWith(".context_length") || key.endsWith(".block_count") || key.endsWith(".embedding_length") ||
            key.endsWith(".attention.head_count") || key.endsWith(".attention.head_count_kv")

    /** Integers and booleans as a Long; floats are read and reported as 0 (no decision uses one). Null for an unknown type. */
    private fun scalar(source: Source, type: Int): Long? = when (type) {
        TYPE_UINT8, TYPE_BOOL -> source.u8().toLong()
        TYPE_INT8 -> source.u8().toByte().toLong()
        TYPE_UINT16 -> source.u16().toLong()
        TYPE_INT16 -> source.u16().toShort().toLong()
        TYPE_UINT32 -> source.u32()
        TYPE_INT32 -> source.u32().toInt().toLong()
        TYPE_UINT64, TYPE_INT64 -> source.u64()
        TYPE_FLOAT32 -> source.u32().let { 0L }
        TYPE_FLOAT64 -> source.u64().let { 0L }
        else -> null
    }

    private fun scalarSize(type: Int): Int? = when (type) {
        TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
        TYPE_UINT16, TYPE_INT16 -> 2
        TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
        TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
        else -> null
    }

    /** Null for an element type this reader does not know (nested arrays included: llama.cpp writes none). */
    private fun skipArray(source: Source): Unit? {
        val elementType = source.u32().toInt()
        val count = source.u64()
        if (count < 0) throw SectionTooLarge()
        if (elementType == TYPE_STRING) {
            for (i in 0 until count) source.skipString()
            return Unit
        }
        val size = scalarSize(elementType) ?: return null
        if (count > MAX_SECTION_BYTES) throw SectionTooLarge()
        source.skip(count * size)
        return Unit
    }

    private class SectionTooLarge : IOException()

    /** Little-endian reads with a running byte budget. */
    private class Source(private val input: InputStream) {
        private var consumed = 0L

        fun readUpTo(buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val r = input.read(buffer, total, buffer.size - total)
                if (r < 0) break
                total += r
            }
            consumed += total
            return total
        }

        fun u8(): Int {
            val b = input.read()
            if (b < 0) throw EOFException()
            charge(1)
            return b
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)

        fun u64(): Long = u32() or (u32() shl 32)

        /** The string, or null when it is longer than [maxBytes] (it is skipped, so reading can go on). */
        fun string(maxBytes: Int): String? {
            val length = u64()
            if (length < 0) throw SectionTooLarge()
            if (length > maxBytes) {
                skip(length)
                return null
            }
            val bytes = ByteArray(length.toInt())
            var total = 0
            while (total < bytes.size) {
                val r = input.read(bytes, total, bytes.size - total)
                if (r < 0) throw EOFException()
                total += r
            }
            charge(length)
            return String(bytes, Charsets.UTF_8)
        }

        fun skipString() {
            val length = u64()
            if (length < 0) throw SectionTooLarge()
            skip(length)
        }

        fun skip(bytes: Long) {
            charge(bytes)
            var left = bytes
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) {
                    left -= skipped
                } else {
                    if (input.read() < 0) throw EOFException()
                    left--
                }
            }
        }

        private fun charge(bytes: Long) {
            consumed += bytes
            if (consumed > MAX_SECTION_BYTES) throw SectionTooLarge()
        }
    }
}

/**
 * Decides, from [GgufMetadata] alone, whether a file can be a chat model for this app's llama.cpp build, and how
 * large a context window to give it. Pure, so every message is JVM-tested.
 *
 * It only refuses what it can name: a shard of a split model, a LoRA adapter, a vision projector, an encoder-only
 * embedding model, a file without a tokenizer. Whether the pinned llama.cpp knows the architecture is not guessed
 * here (a hard-coded list would go stale); llama.cpp decides at load time and its own reason is shown.
 */
object GgufCompatibility {
    /** Null when nothing rules the file out. Otherwise what is wrong and what to do about it. */
    fun problem(metadata: GgufMetadata): String? {
        val arch = metadata.architecture?.lowercase()
        val split = metadata.splitCount ?: 0
        return when {
            split > 1 ->
                "This file is one part of a model split into $split files. Merge the parts into a single .gguf on your PC " +
                    "(llama.cpp: llama-gguf-split --merge), or download a single-file version, then import that."
            metadata.type == "adapter" ->
                "This file is a LoRA adapter, not a complete model. Import the base model's .gguf instead."
            metadata.type == "mmproj" || metadata.type == "projector" || arch == "clip" ->
                "This file is a vision projector (mmproj), not a chat model. Import the main model .gguf that came with it."
            arch != null && arch.contains("bert") ->
                "This is an embedding model ($arch), not a chat model. Pick an instruct or chat .gguf."
            !metadata.hasTokenizer ->
                "This GGUF has no tokenizer, so it cannot be used for chat. Pick an instruct or chat .gguf."
            else -> null
        }
    }

    /** A non-blocking heads-up for a file [problem] accepts, or null. */
    fun warning(metadata: GgufMetadata): String? =
        if (metadata.hasChatTemplate) {
            null
        } else {
            "This model has no built-in chat template, so a generic one (ChatML) is used; replies may be poor. An instruct or chat version works better."
        }

    /** The window to load with: what was asked for, but never more than the model was trained with. */
    fun contextTokens(requested: Int, metadata: GgufMetadata?): Int {
        val trained = metadata?.contextLength ?: return requested
        return minOf(requested, trained).coerceAtLeast(1)
    }
}
