package ai.droidcommand.hackerai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvidenceReferenceTest {
    @Test
    fun `strips file prefix`() {
        assertEquals("src/main.py", evidenceFilePath("file:src/main.py"))
    }

    @Test
    fun `strips line number suffix`() {
        assertEquals("src/main.py", evidenceFilePath("file:src/main.py:42"))
    }

    @Test
    fun `strips line range suffix`() {
        assertEquals("src/main.py", evidenceFilePath("file:src/main.py:10-20"))
    }

    @Test
    fun `rejects http URLs`() {
        assertNull(evidenceFilePath("http://example.com/file.py"))
    }

    @Test
    fun `rejects https URLs`() {
        assertNull(evidenceFilePath("https://example.com/file.py"))
    }

    @Test
    fun `rejects UNC paths`() {
        assertNull(evidenceFilePath("//server/share/file.py"))
    }

    @Test
    fun `returns plain path unchanged`() {
        assertEquals("src/main.py", evidenceFilePath("src/main.py"))
    }

    @Test
    fun `file prefix with URL does not become URL after stripping prefix`() {
        // After stripping "file:" → "https://..." which is a URL → null
        assertNull(evidenceFilePath("file:https://example.com/file"))
    }

    @Test
    fun `handles deeply nested path with line number`() {
        assertEquals("a/b/c/d.ts", evidenceFilePath("file:a/b/c/d.ts:100"))
    }
}
