package ai.droidcommand.codeedit

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ED-1 .. ED-8: what the editor guarantees beyond "applies one matching block". */
class EditValidationTest {
    private fun fixture(content: String, name: String = "Foo.kt"): Pair<Path, Path> {
        val root = createTempDirectory("edit-validation")
        val file = root.resolve(name)
        file.writeText(content)
        return root to file
    }

    private fun statuses(result: EditResult): List<BlockStatus> = when (result) {
        is EditResult.Applied -> result.reports.map { it.status }
        is EditResult.Rejected -> result.reports.map { it.status }
    }

    @Test
    fun `ED-2 reports every problem in the batch not just the first`() {
        val original = "dup\nx\ndup\nkeep\n"
        val (root, file) = fixture(original)

        val result = SearchReplaceEditor(listOf(root)).apply(
            file,
            listOf(EditBlock("missing", "y"), EditBlock("dup", "z"), EditBlock("keep", "kept")),
        )

        assertIs<EditResult.Rejected>(result)
        assertEquals(listOf(BlockStatus.NOT_FOUND, BlockStatus.AMBIGUOUS, BlockStatus.MATCHED), statuses(result))
        assertEquals(listOf(1, 3), result.reports[1].lines)
        assertContains(result.reason, "Block 1: NOT_FOUND")
        assertContains(result.reason, "Block 2: AMBIGUOUS")
        assertContains(result.reason, "lines 1, 3")
        assertContains(result.reason, "not applied because the batch was rejected")
        assertEquals(original, file.readText())
    }

    @Test
    fun `ED-2 overlapping occurrences of the search text count as ambiguous`() {
        val (root, file) = fixture("aaa\n")
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("aa", "b")))
        assertIs<EditResult.Rejected>(result)
        assertEquals(listOf(BlockStatus.AMBIGUOUS), statuses(result))
        assertEquals("aaa\n", file.readText())
    }

    @Test
    fun `ED-2 not-found reports a whitespace-insensitive near miss with its line`() {
        val original = "fun f() {\n    if (a) {\n        b()\n    }\n}\n"
        val (root, file) = fixture(original)

        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("if (a) {\n  b()\n}", "x")))

        assertIs<EditResult.Rejected>(result)
        assertEquals(BlockStatus.NOT_FOUND, result.reports[0].status)
        assertEquals(listOf(2), result.reports[0].lines)
        assertContains(result.reason, "whitespace-insensitive match exists at line 2")
        assertEquals(original, file.readText())
    }

    @Test
    fun `ED-2 an empty search text is reported and rejected`() {
        val (root, file) = fixture("a\n")
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("", "x")))
        assertIs<EditResult.Rejected>(result)
        assertEquals(listOf(BlockStatus.EMPTY_SEARCH), statuses(result))
    }

    @Test
    fun `ED-3 blocks match the original content not earlier blocks' output`() {
        val (root, file) = fixture("foo\n")
        val result = SearchReplaceEditor(listOf(root)).apply(
            file,
            listOf(EditBlock("foo", "bar"), EditBlock("bar", "baz")),
        )
        assertIs<EditResult.Rejected>(result)
        assertEquals(listOf(BlockStatus.MATCHED, BlockStatus.NOT_FOUND), statuses(result))
        assertEquals("foo\n", file.readText())
    }

    @Test
    fun `ED-3 overlapping matches are rejected and adjacent ones are fine`() {
        val (root, file) = fixture("abcdef\n")
        val editor = SearchReplaceEditor(listOf(root))

        val overlapping = editor.apply(file, listOf(EditBlock("abcd", "1"), EditBlock("cdef", "2")))
        assertIs<EditResult.Rejected>(overlapping)
        assertEquals(listOf(BlockStatus.MATCHED, BlockStatus.OVERLAP), statuses(overlapping))
        assertEquals("abcdef\n", file.readText())

        val adjacent = editor.apply(file, listOf(EditBlock("abc", "1"), EditBlock("def", "2")))
        assertIs<EditResult.Applied>(adjacent)
        assertEquals("12\n", file.readText())
    }

    @Test
    fun `ED-3 blocks may be supplied in any order`() {
        val (root, file) = fixture("one\ntwo\nthree\n")
        val result = SearchReplaceEditor(listOf(root)).apply(
            file,
            listOf(EditBlock("three", "3"), EditBlock("one", "1")),
        )
        assertIs<EditResult.Applied>(result)
        assertEquals("1\ntwo\n3\n", file.readText())
    }

    @Test
    fun `ED-4 a successful edit returns a unified diff labelled relative to the root`() {
        val (root, file) = fixture("val a = 1\nval b = 2\n")
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("val a = 1", "val a = 10")))
        assertIs<EditResult.Applied>(result)
        assertContains(result.diff, "--- a/Foo.kt")
        assertContains(result.diff, "+++ b/Foo.kt")
        assertContains(result.diff, "-val a = 1\n")
        assertContains(result.diff, "+val a = 10\n")
        assertTrue(result.written)
    }

    @Test
    fun `ED-4 an edit that changes nothing is rejected`() {
        val (root, file) = fixture("same\n")
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("same", "same")))
        assertIs<EditResult.Rejected>(result)
        assertContains(result.reason, "would not change")
    }

    @Test
    fun `ED-5 a dry run reports and diffs but never writes`() {
        val original = "val a = 1\n"
        val (root, file) = fixture(original)
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("val a = 1", "val a = 2")), dryRun = true)
        assertIs<EditResult.Applied>(result)
        assertFalse(result.written)
        assertContains(result.diff, "+val a = 2")
        assertEquals(original, file.readText())
    }

    @Test
    fun `ED-5 a dry run still rejects a bad batch`() {
        val (root, file) = fixture("a\n")
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("zzz", "b")), dryRun = true)
        assertIs<EditResult.Rejected>(result)
    }

    @Test
    fun `ED-6 a symlink inside the root that points outside it is rejected`() {
        val root = createTempDirectory("edit-root")
        val outside = createTempDirectory("edit-outside")
        val secret = outside.resolve("secret.txt")
        secret.writeText("token=1\n")
        val link = root.resolve("innocent.txt")
        try {
            Files.createSymbolicLink(link, secret)
        } catch (_: UnsupportedOperationException) {
            return // symlinks unsupported on this filesystem
        } catch (_: java.io.IOException) {
            return
        }

        val result = SearchReplaceEditor(listOf(root)).apply(link, listOf(EditBlock("token=1", "token=2")))

        assertIs<EditResult.Rejected>(result)
        assertContains(result.reason, "outside every authorized root")
        assertEquals("token=1\n", secret.readText())
    }

    @Test
    fun `ED-6 a symlink that stays inside the root is edited through and remains a symlink`() {
        val root = createTempDirectory("edit-root")
        val real = root.resolve("real.txt")
        real.writeText("v=1\n")
        val link = root.resolve("alias.txt")
        try {
            Files.createSymbolicLink(link, real)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.io.IOException) {
            return
        }

        val result = SearchReplaceEditor(listOf(root)).apply(link, listOf(EditBlock("v=1", "v=2")))

        assertIs<EditResult.Applied>(result)
        assertEquals("v=2\n", real.readText())
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `ED-6 a directory is not an editable target`() {
        val root = createTempDirectory("edit-root")
        val result = SearchReplaceEditor(listOf(root)).apply(root, listOf(EditBlock("a", "b")))
        assertIs<EditResult.Rejected>(result)
    }

    @Test
    fun `ED-7 invalid UTF-8 is rejected and left byte-identical`() {
        val root = createTempDirectory("edit-root")
        val file = root.resolve("latin1.txt")
        val bytes = byteArrayOf('a'.code.toByte(), 0xE9.toByte(), 'b'.code.toByte(), '\n'.code.toByte())
        file.writeBytes(bytes)

        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("a", "x")))

        assertIs<EditResult.Rejected>(result)
        assertContains(result.reason, "not valid UTF-8")
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun `ED-7 a file containing NUL bytes is rejected as binary`() {
        val root = createTempDirectory("edit-root")
        val file = root.resolve("blob.bin")
        file.writeBytes(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte()))
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("a", "x")))
        assertIs<EditResult.Rejected>(result)
        assertContains(result.reason, "NUL")
    }

    @Test
    fun `ED-7 CRLF files match as LF and are written back as CRLF`() {
        val root = createTempDirectory("edit-root")
        val file = root.resolve("win.txt")
        file.writeBytes("one\r\ntwo\r\nthree\r\n".toByteArray())

        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("one\ntwo", "1\n2\n2b")))

        assertIs<EditResult.Applied>(result)
        assertEquals("1\r\n2\r\n2b\r\nthree\r\n", String(file.readBytes()))
    }

    @Test
    fun `ED-7 a file that mixes CRLF and LF is rejected`() {
        val root = createTempDirectory("edit-root")
        val file = root.resolve("mixed.txt")
        file.writeBytes("one\r\ntwo\n".toByteArray())
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("one", "1")))
        assertIs<EditResult.Rejected>(result)
        assertContains(result.reason, "mixes CRLF and LF")
    }

    @Test
    fun `ED-7 a UTF-8 byte order mark survives the edit`() {
        val root = createTempDirectory("edit-root")
        val file = root.resolve("bom.txt")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + "val a = 1\n".toByteArray())

        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("val a = 1", "val a = 2")))

        assertIs<EditResult.Applied>(result)
        val after = file.readBytes()
        assertTrue(bom.contentEquals(after.copyOfRange(0, 3)))
        assertEquals("val a = 2\n", String(after, 3, after.size - 3))
    }

    @Test
    fun `ED-7 non-ASCII text round-trips`() {
        val (root, file) = fixture("naïve — 日本語 = 1\n")
        val result = SearchReplaceEditor(listOf(root)).apply(file, listOf(EditBlock("= 1", "= 2")))
        assertIs<EditResult.Applied>(result)
        assertEquals("naïve — 日本語 = 2\n", file.readText())
    }

    @Test
    fun `ED-7 a file over the size limit is rejected`() {
        val (root, file) = fixture("0123456789")
        val result = SearchReplaceEditor(listOf(root), maxFileBytes = 5).apply(file, listOf(EditBlock("0", "x")))
        assertIs<EditResult.Rejected>(result)
        assertContains(result.reason, "edit limit")
        assertEquals("0123456789", file.readText())
    }

    @Test
    fun `ED-8 POSIX permissions are preserved and no temp file is left behind`() {
        val (root, file) = fixture("a\n")
        val wanted = PosixFilePermissions.fromString("rw-r-----")
        try {
            Files.setPosixFilePermissions(file, wanted)
        } catch (_: UnsupportedOperationException) {
            return
        }

        val editor = SearchReplaceEditor(listOf(root))
        assertIs<EditResult.Applied>(editor.apply(file, listOf(EditBlock("a", "b"))))
        assertEquals(wanted, Files.getPosixFilePermissions(file))

        // A rejected edit must not leave temp files either.
        assertIs<EditResult.Rejected>(editor.apply(file, listOf(EditBlock("nope", "c"))))
        assertEquals(listOf("Foo.kt"), root.listDirectoryEntries().map { it.fileName.toString() })
    }
}
