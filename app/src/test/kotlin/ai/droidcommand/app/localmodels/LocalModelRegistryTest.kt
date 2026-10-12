package ai.droidcommand.app.localmodels

import ai.droidcommand.llm.local.ModelMetadata
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalModelRegistryTest {
    private fun newDir(): File = Files.createTempDirectory("registry-test").toFile()

    /** Writes a stand-in model file for [id] and returns the record that describes it. */
    private fun modelIn(dir: File, id: String): ImportedModel {
        val fileName = "$id.gguf"
        File(dir, fileName).writeBytes("GGUF".toByteArray())
        return ImportedModel(
            metadata = ModelMetadata(
                id = id,
                displayName = "Model $id",
                fileName = fileName,
                sha256 = "b".repeat(64),
                contextTokens = 2048,
                sizeBytes = 4L,
            ),
            importedAtMillis = 1L,
        )
    }

    @Test
    fun `save then load returns the same models in the same order`() {
        val dir = newDir()
        try {
            val a = modelIn(dir, "user-aaaaaaaaaaaaaaaa")
            val b = modelIn(dir, "user-bbbbbbbbbbbbbbbb")
            LocalModelRegistry(dir).save(listOf(a, b))
            assertEquals(listOf(a, b), LocalModelRegistry(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a record whose model file has gone is not loaded`() {
        val dir = newDir()
        try {
            val a = modelIn(dir, "user-aaaaaaaaaaaaaaaa")
            val b = modelIn(dir, "user-bbbbbbbbbbbbbbbb")
            LocalModelRegistry(dir).save(listOf(a, b))
            File(dir, b.metadata.fileName).delete()
            assertEquals(listOf(a), LocalModelRegistry(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `malformed lines are skipped and the good records survive`() {
        val dir = newDir()
        try {
            val a = modelIn(dir, "user-aaaaaaaaaaaaaaaa")
            File(dir, LocalModelRegistry.FILE_NAME).writeText("garbage\n\n" + ImportedModelCodec.encode(a) + "\nmore garbage\n")
            assertEquals(listOf(a), LocalModelRegistry(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `saving leaves no temporary file behind`() {
        val dir = newDir()
        try {
            LocalModelRegistry(dir).save(listOf(modelIn(dir, "user-aaaaaaaaaaaaaaaa")))
            assertFalse(File(dir, "${LocalModelRegistry.FILE_NAME}.tmp").exists())
            assertTrue(File(dir, LocalModelRegistry.FILE_NAME).isFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
