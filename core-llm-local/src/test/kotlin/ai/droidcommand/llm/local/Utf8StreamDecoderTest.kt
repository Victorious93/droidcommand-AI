package ai.droidcommand.llm.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Utf8StreamDecoderTest {
    private fun decodeInChunks(bytes: ByteArray, size: Int): String {
        val d = Utf8StreamDecoder()
        val sb = StringBuilder()
        bytes.toList().chunked(size).forEach { sb.append(d.push(it.toByteArray())) }
        return sb.append(d.flush()).toString()
    }

    @Test fun `multi-byte characters survive every chunk size`() {
        val text = "héllo wörld 😀 日本語 ok"
        val bytes = text.toByteArray(Charsets.UTF_8)
        for (size in 1..bytes.size) assertEquals(text, decodeInChunks(bytes, size), "chunk=$size")
    }

    @Test fun `incomplete character is held back not emitted`() {
        val emoji = "😀".toByteArray(Charsets.UTF_8)
        val d = Utf8StreamDecoder()
        assertEquals("", d.push(emoji.copyOfRange(0, 2)))
        assertEquals("😀", d.push(emoji.copyOfRange(2, 4)))
    }

    @Test fun `truncated stream flushes as replacement character`() {
        val d = Utf8StreamDecoder()
        d.push("😀".toByteArray(Charsets.UTF_8).copyOfRange(0, 3))
        assertEquals("�", d.flush())
        assertEquals("", d.flush())
    }

    @Test fun `invalid bytes do not throw`() {
        val d = Utf8StreamDecoder()
        assertTrue(d.push(byteArrayOf(0xFF.toByte(), 'a'.code.toByte())).endsWith("a"))
    }
}
