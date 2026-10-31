package ai.droidcommand.llm.local

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GgufStorageScannerTest {
    @Test
    fun `finds gguf files in Download, Download-models, Documents-models and models`() {
        val root = Files.createTempDirectory("gguf-scan").toFile()
        try {
            FileTree(root).apply {
                file("Download", "tiny.gguf", 11)
                file("Download", "notes.txt", 4)
                file("Download", "models", "qwen.Q4_K_M.gguf", 22)
                file("Documents", "models", "phi.gguf", 33)
                file("models", "smol.gguf", 44)
                file("models", ".hidden", "skip.gguf", 55)
            }
            val scanned = GgufStorageScanner.scan(GgufStorageScanner.commonRoots(root))
            val names = scanned.map { it.fileName }.sorted()
            assertEquals(listOf("phi.gguf", "qwen.Q4_K_M.gguf", "smol.gguf", "tiny.gguf"), names)
            assertEquals("Q4_K_M", scanned.first { it.fileName.startsWith("qwen") }.quant)
            assertTrue(scanned.all { it.sizeBytes > 0L })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing and unreadable roots are skipped and the cap is honoured`() {
        val root = Files.createTempDirectory("gguf-scan-cap").toFile()
        try {
            repeat(8) { i -> java.io.File(root, "m$i.gguf").writeText("GGUF$i") }
            val scanned = GgufStorageScanner.scan(listOf(java.io.File(root, "nope"), root), maxFiles = 3)
            assertEquals(3, scanned.size)
            assertEquals(emptyList(), GgufStorageScanner.scan(listOf(java.io.File(root, "missing"))))
            assertEquals(emptyList(), GgufStorageScanner.commonRoots(null))
        } finally {
            root.deleteRecursively()
        }
    }

    private class FileTree(private val root: java.io.File) {
        fun file(vararg partsAndName: Any) {
            val bits = partsAndName.toList()
            val size = bits.last() as Int
            val rel = bits.dropLast(1).map { it as String }
            val file = rel.fold(root) { dir, name -> java.io.File(dir, name) }
            file.parentFile.mkdirs()
            file.writeBytes(ByteArray(size) { 'G'.code.toByte() })
        }
    }
}
