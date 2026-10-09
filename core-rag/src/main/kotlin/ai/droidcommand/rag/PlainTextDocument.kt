package ai.droidcommand.rag

/** Why a file could not be read as a text document. */
class DocumentReadException(message: String) : RuntimeException(message)

/**
 * Reads plain-text / Markdown bytes into a string. Deliberately strict: rejects files over [maxBytes]
 * (so a huge file can't exhaust memory when embedded), files containing NUL bytes (almost certainly binary,
 * e.g. a PDF mislabelled as text), and invalid UTF-8 (rather than silently inserting replacement characters).
 * PDF is a separate, Android-only extractor and is not handled here.
 */
object PlainTextDocument {
    const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024

    fun read(bytes: ByteArray, maxBytes: Int = DEFAULT_MAX_BYTES): String {
        if (bytes.size > maxBytes) throw DocumentReadException("File is larger than ${maxBytes / 1024} KB.")
        if (bytes.any { it == 0.toByte() }) throw DocumentReadException("File looks binary, not text.")
        val text = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw DocumentReadException("File is not valid UTF-8 text.")
        }
        return text.removePrefix("﻿")
    }
}
