package ai.droidcommand.app.localmodels

import ai.droidcommand.llm.local.ModelMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ImportedModelCodecTest {
    private val sample = ImportedModel(
        metadata = ModelMetadata(
            id = "user-0123456789abcdef",
            displayName = "Tab\tand new\nline, café",
            fileName = "user-0123456789abcdef.gguf",
            sha256 = "a".repeat(64),
            contextTokens = 2048,
            sizeBytes = 123_456L,
        ),
        importedAtMillis = 1_700_000_000_000L,
    )

    @Test
    fun `round-trips every field, including tabs, newlines and non-ASCII text`() {
        val line = ImportedModelCodec.encode(sample)
        assertFalse(line.contains('\n'))
        assertEquals(sample, ImportedModelCodec.decode(line))
    }

    @Test
    fun `lines that are not complete valid records decode to null`() {
        assertNull(ImportedModelCodec.decode(""))
        assertNull(ImportedModelCodec.decode("only\tthree\tfields"))
        // The digest is not 64 hex characters, so ModelMetadata rejects the record.
        val badDigest = listOf("user-1", "name", "user-1.gguf", "not-a-digest", "2048", "10", "5").joinToString("\t")
        assertNull(ImportedModelCodec.decode(badDigest))
    }

    @Test
    fun `an eighth field round-trips an in-place shared-storage path`() {
        val withPath = sample.copy(externalPath = "/storage/emulated/0/Download/tiny.gguf")
        assertEquals(withPath, ImportedModelCodec.decode(ImportedModelCodec.encode(withPath)))
        assertEquals(sample, ImportedModelCodec.decode(ImportedModelCodec.encode(sample)))
        assertNull(ImportedModelCodec.decode(ImportedModelCodec.encode(sample) + "\textra\tninth"))
    }
}
