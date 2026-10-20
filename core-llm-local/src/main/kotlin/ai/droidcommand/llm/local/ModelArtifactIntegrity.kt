// Design adapted from OpenDroid (https://github.com/Victorious93/opendroid),
// app/src/main/java/com/opendroid/ai/core/llm/ModelArtifactIntegrity.kt
// Copyright (c) 2026 OpenDroid Contributors, licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Reimplemented, not copied: what carries over is the failure
// taxonomy, the cheap-checks-before-hash ordering, and the streaming digest behind an injectable seam. OpenDroid's
// LiteRT-LM runtime check, its Room/manifest install record and its Hugging Face-token plumbing are not ported;
// the format check here is the GGUF header llama.cpp requires.
package ai.droidcommand.llm.local

import java.io.File
import java.io.IOException

enum class IntegrityFailure {
    MISSING_FILE,
    UNREADABLE_FILE,
    SIZE_MISMATCH,
    FORMAT_INVALID,
    HASH_MISMATCH,
    HASH_UNAVAILABLE,
}

sealed interface IntegrityResult {
    data object Valid : IntegrityResult

    /** [actualSha256] is set only for [IntegrityFailure.HASH_MISMATCH]. [detail] is safe to show to the user. */
    data class Invalid(val failure: IntegrityFailure, val detail: String, val actualSha256: String? = null) : IntegrityResult
}

/** Computes the lowercase-hex SHA-256 of a file. A seam so tests can count or fail hashing without gigabyte files. */
fun interface FileHasher {
    @Throws(IOException::class)
    fun sha256(file: File): String
}

object StreamingSha256 : FileHasher {
    override fun sha256(file: File): String = file.inputStream().use(ModelRepository::sha256Hex)
}

/**
 * Decides whether a model file on disk is the file the metadata describes. Checks run cheapest first, so a
 * wrong-sized or non-GGUF file (an HTML error page, a truncated download) is rejected without hashing gigabytes:
 * present and readable, then exact size (when [expectedSizeBytes] is known), then the GGUF header (when
 * [requireGguf]), then SHA-256 against [expectedSha256]. Only a file that passes all of them is [IntegrityResult.Valid].
 *
 * The header check is a sanity check, not a security one: llama.cpp parses the rest of the file natively, so a file
 * with a valid header and a matching digest is trusted only as far as the publisher's digest is.
 */
class ModelArtifactIntegrity(
    private val hasher: FileHasher = StreamingSha256,
    private val requireGguf: Boolean = true,
) {
    fun verify(file: File, expectedSizeBytes: Long?, expectedSha256: String): IntegrityResult {
        if (!file.isFile) return IntegrityResult.Invalid(IntegrityFailure.MISSING_FILE, "Model file not found.")
        if (!file.canRead()) return IntegrityResult.Invalid(IntegrityFailure.UNREADABLE_FILE, "Model file cannot be read.")
        if (expectedSizeBytes != null && file.length() != expectedSizeBytes) {
            return IntegrityResult.Invalid(
                IntegrityFailure.SIZE_MISMATCH,
                "Downloaded ${file.length()} bytes, expected $expectedSizeBytes",
            )
        }
        if (requireGguf) {
            val header = try {
                GgufHeader.read(file)
            } catch (e: IOException) {
                return IntegrityResult.Invalid(IntegrityFailure.UNREADABLE_FILE, "Model file cannot be read: ${e.message ?: "I/O error"}")
            }
            if (header is GgufHeaderResult.Invalid) return IntegrityResult.Invalid(IntegrityFailure.FORMAT_INVALID, header.reason)
        }
        val actual = try {
            hasher.sha256(file)
        } catch (e: IOException) {
            return IntegrityResult.Invalid(IntegrityFailure.HASH_UNAVAILABLE, "Could not hash the model file: ${e.message ?: "I/O error"}")
        }
        return if (actual == expectedSha256) {
            IntegrityResult.Valid
        } else {
            IntegrityResult.Invalid(IntegrityFailure.HASH_MISMATCH, "SHA-256 mismatch (got $actual)", actual)
        }
    }
}
