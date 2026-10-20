package ai.droidcommand.llm.local

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GgufHeaderTest {
    private fun invalid(bytes: ByteArray): String = assertIs<GgufHeaderResult.Invalid>(GgufHeader.parse(bytes)).reason

    @Test
    fun `accepts v2 and v3 headers and reports their fields`() {
        for (v in listOf(2, 3)) {
            val r = assertIs<GgufHeaderResult.Valid>(GgufHeader.parse(ggufBytes(version = v, tensors = 291, entries = 24)))
            assertEquals(GgufHeader(v, 291, 24), r.header)
        }
    }

    @Test
    fun `rejects a file without the magic, such as an HTML error page`() {
        assertTrue(invalid("<!DOCTYPE html><html>403 Forbidden</html>".toByteArray()).contains("magic"))
        assertTrue(invalid(ggufBytes(magic = "GGML")).contains("magic"))
    }

    @Test
    fun `rejects a file shorter than the header`() {
        assertTrue(invalid("GGUF".toByteArray()).contains("shorter"))
        assertTrue(invalid(ByteArray(0)).contains("shorter"))
    }

    @Test
    fun `rejects unsupported versions including the legacy v1`() {
        assertTrue(invalid(ggufBytes(version = 1)).contains("Unsupported GGUF version 1"))
        assertTrue(invalid(ggufBytes(version = 4)).contains("Unsupported GGUF version 4"))
    }

    @Test
    fun `rejects a big-endian file`() {
        // Version 3 written big-endian reads as 0x03000000 little-endian: low 16 bits zero.
        val be = ggufBytes(version = 3)
        byteArrayOf(0, 0, 0, 3).copyInto(be, destinationOffset = 4)
        assertTrue(invalid(be).contains("Big-endian"))
    }

    @Test
    fun `rejects implausible tensor and entry counts`() {
        assertTrue(invalid(ggufBytes(tensors = 0)).contains("tensor count"))
        assertTrue(invalid(ggufBytes(tensors = -1)).contains("tensor count"))
        assertTrue(invalid(ggufBytes(tensors = GgufHeader.MAX_COUNT + 1)).contains("tensor count"))
        assertTrue(invalid(ggufBytes(entries = 0)).contains("metadata entry count"))
        assertTrue(invalid(ggufBytes(entries = Long.MIN_VALUE)).contains("metadata entry count"))
    }

    @Test
    fun `reads the header from a file and tolerates a file shorter than the header`() {
        val dir = Files.createTempDirectory("gguf").toFile().also { it.deleteOnExit() }
        val good = File(dir, "good.gguf").also { it.writeBytes(ggufBytes()) }
        val short = File(dir, "short.gguf").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertIs<GgufHeaderResult.Valid>(GgufHeader.read(good))
        assertIs<GgufHeaderResult.Invalid>(GgufHeader.read(short))
    }
}
