package ai.droidcommand.llm.local

/**
 * Turns a stream of byte chunks into text without ever splitting a multi-byte UTF-8 character:
 * llama.cpp tokens can end in the middle of a character (an emoji is often several tokens), so
 * decoding each token's bytes independently would emit replacement characters. Incomplete
 * trailing bytes are held back until the rest arrives. Not thread-safe.
 */
class Utf8StreamDecoder {
    private var pending = ByteArray(0)

    fun push(bytes: ByteArray): String {
        val buf = pending + bytes
        val keep = incompleteTail(buf)
        pending = buf.copyOfRange(buf.size - keep, buf.size)
        return String(buf, 0, buf.size - keep, Charsets.UTF_8)
    }

    /** Decodes whatever is left; a truncated character becomes U+FFFD. */
    fun flush(): String {
        val out = String(pending, Charsets.UTF_8)
        pending = ByteArray(0)
        return out
    }

    private fun incompleteTail(buf: ByteArray): Int {
        for (i in 1..minOf(3, buf.size)) {
            val b = buf[buf.size - i].toInt() and 0xFF
            if (b and 0xC0 == 0x80) continue // continuation byte: keep looking for its lead
            val need = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            return if (need > i) i else 0
        }
        return 0
    }
}
