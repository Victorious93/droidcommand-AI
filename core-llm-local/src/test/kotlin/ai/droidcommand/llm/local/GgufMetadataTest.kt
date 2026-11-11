package ai.droidcommand.llm.local

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure-Kotlin GGUF metadata reader and the compatibility rules built on it. No llama.cpp is involved. */
class GgufMetadataTest {
    private fun read(bytes: ByteArray) = GgufMetadataReader.read(ByteArrayInputStream(bytes))

    private fun valid(bytes: ByteArray) = assertIs<GgufMetadataResult.Valid>(read(bytes)).metadata

    private fun invalid(bytes: ByteArray) = assertIs<GgufMetadataResult.Invalid>(read(bytes)).reason

    @Test
    fun `reads the architecture, trained context and shape, skipping the vocabulary`() {
        val m = valid(ggufModelBytes())
        assertEquals("llama", m.architecture)
        assertEquals("Test Model", m.name)
        assertEquals(4096, m.contextLength)
        assertEquals(16, m.blockCount)
        assertEquals(2048, m.embeddingLength)
        assertEquals(32, m.headCount)
        assertEquals(8, m.headCountKv)
        assertTrue(m.hasTokenizer)
        assertTrue(m.hasChatTemplate)
        assertNull(m.splitCount)
        assertNull(GgufCompatibility.problem(m))
        assertNull(GgufCompatibility.warning(m))
    }

    @Test
    fun `cache cost per token comes from the model's own shape`() {
        // Llama-3.2-1B's shape: 16 layers, key and value of 2048 / 32 * 8 = 512 half-floats each.
        assertEquals(16L * 2 * 512 * 2, valid(ggufModelBytes()).kvBytesPerToken())
    }

    @Test
    fun `a file that ends inside its metadata is reported as incomplete`() {
        val whole = ggufModelBytes(payload = ByteArray(0))
        for (cut in listOf(30, whole.size / 2, whole.size - 1)) {
            assertTrue(invalid(whole.copyOf(cut)).contains("incomplete"), "cut at $cut")
        }
    }

    @Test
    fun `header problems are reported with the header's own reason`() {
        assertTrue(invalid(ggufBytes(version = 1)).contains("Unsupported GGUF version 1"))
        assertTrue(invalid("<html>".toByteArray()).contains("shorter"))
        assertTrue(invalid("<!DOCTYPE html><html>403 Forbidden</html>".toByteArray()).contains("magic"))
    }

    @Test
    fun `absurd lengths end the read instead of allocating or looping`() {
        val hugeString = ggufModelBytes { stringWithDeclaredLength("general.url", Long.MAX_VALUE / 2) }
        assertTrue(invalid(hugeString).contains("implausibly large"))
        val negative = ggufModelBytes { stringWithDeclaredLength("general.url", -5) }
        assertTrue(invalid(negative).contains("implausibly large"))
        // A long string that is really there is skipped, not kept, and reading continues.
        val long = valid(ggufModelBytes { string("general.description", "x".repeat(300_000)) })
        assertEquals("llama", long.architecture)
    }

    @Test
    fun `files that are not chat models are named with what to do instead`() {
        fun problem(bytes: ByteArray) = assertNotNull(GgufCompatibility.problem(valid(bytes)))
        assertTrue(problem(ggufModelBytes { u16("split.count", 3) }).contains("split into 3 files"))
        assertTrue(problem(ggufModelBytes { string("general.type", "adapter") }).contains("LoRA adapter"))
        assertTrue(problem(ggufModelBytes(architecture = "clip", tokenizer = false)).contains("vision projector"))
        assertTrue(problem(ggufModelBytes { string("general.type", "mmproj") }).contains("vision projector"))
        assertTrue(problem(ggufModelBytes(architecture = "nomic-bert")).contains("embedding model"))
        assertTrue(problem(ggufModelBytes(tokenizer = false)).contains("no tokenizer"))
        // One shard out of one is an ordinary single-file model.
        assertNull(GgufCompatibility.problem(valid(ggufModelBytes { u16("split.count", 1) })))
    }

    @Test
    fun `a model without a chat template is accepted with a warning`() {
        val m = valid(ggufModelBytes(chatTemplate = false))
        assertFalse(m.hasChatTemplate)
        assertNull(GgufCompatibility.problem(m))
        assertTrue(assertNotNull(GgufCompatibility.warning(m)).contains("chat template"))
    }

    @Test
    fun `the window is never larger than the model was trained with`() {
        assertEquals(2048, GgufCompatibility.contextTokens(2048, valid(ggufModelBytes(contextLength = 4096))))
        assertEquals(512, GgufCompatibility.contextTokens(2048, valid(ggufModelBytes(contextLength = 512))))
        assertEquals(2048, GgufCompatibility.contextTokens(2048, valid(ggufModelBytes(contextLength = null))))
        assertEquals(2048, GgufCompatibility.contextTokens(2048, null))
    }

    @Test
    fun `the RAM estimate grows with the real cache cost`() {
        val mb = 1024L * 1024L
        val floor = RamFit.estimateBytes(700 * mb, 8192)
        val real = RamFit.estimateBytes(700 * mb, 8192, valid(ggufModelBytes()).kvBytesPerToken())
        assertEquals(8192L * 32_768L - 8192L * RamFit.KV_BYTES_PER_TOKEN, real - floor)
        // Unknown shape, or a figure below the floor, keeps the floor.
        assertEquals(floor, RamFit.estimateBytes(700 * mb, 8192, 1L))
    }
}
