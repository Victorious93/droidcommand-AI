package ai.droidcommand.repomap

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RM-8 (what gets indexed and what is never touched) and RM-9 (incremental re-indexing). */
class RepoIndexerTest {
    private fun tempRoot(): Path = createTempDirectory("repomap-test").toRealPath()

    private fun Path.write(relative: String, text: String): Path {
        val file = resolve(relative)
        Files.createDirectories(file.parent)
        file.writeText(text)
        return file
    }

    private fun RepoIndex.paths() = files.map { it.path }

    // ------------------------------------------------------------------ RM-8

    @Test
    fun `RM-8 indexes supported files with sorted slash-separated relative paths`() {
        val root = tempRoot()
        root.write("src/b/Two.kt", "class Two")
        root.write("src/a/One.java", "class One {}")
        root.write("tool.py", "def tool(): pass")
        root.write("README.md", "class NotCode")
        root.write("data.bin", "class AlsoNotCode")

        val index = RepoIndexer(listOf(root)).index(root)

        assertEquals(listOf("src/a/One.java", "src/b/Two.kt", "tool.py"), index.paths())
        assertEquals(0, index.skipped)
        assertFalse(index.truncated)
    }

    @Test
    fun `RM-8 build and dependency directories are pruned at any depth`() {
        val root = tempRoot()
        root.write("src/Real.kt", "class Real")
        for (dir in listOf("build", ".git", "node_modules", ".gradle", "src/build", "web/node_modules", "__pycache__", ".venv", "target")) {
            root.write("$dir/Generated.kt", "class Generated")
        }
        assertEquals(listOf("src/Real.kt"), RepoIndexer(listOf(root)).index(root).paths())
    }

    @Test
    fun `RM-8 oversized files are skipped and counted`() {
        val root = tempRoot()
        root.write("Small.kt", "class Small")
        root.write("Big.kt", "class Big\n" + "// padding\n".repeat(50))

        val index = RepoIndexer(listOf(root), maxFileBytes = 100).index(root)

        assertEquals(listOf("Small.kt"), index.paths())
        assertEquals(1, index.skipped)
    }

    @Test
    fun `RM-8 files that are not UTF-8 text are skipped and counted`() {
        val root = tempRoot()
        root.write("Good.kt", "class Good")
        root.resolve("Latin1.kt").writeBytes(byteArrayOf('c'.code.toByte(), 0xC3.toByte(), 0x28))
        root.resolve("Nul.kt").writeBytes("class A\u0000".toByteArray())

        val index = RepoIndexer(listOf(root)).index(root)

        assertEquals(listOf("Good.kt"), index.paths())
        assertEquals(2, index.skipped)
    }

    @Test
    fun `RM-8 symlinked files and directories are never followed`() {
        val outside = tempRoot()
        outside.write("Secret.kt", "class OutsideSecret")
        outside.write("dir/Deep.kt", "class OutsideDeep")
        val root = tempRoot()
        root.write("Real.kt", "class Real")
        Files.createSymbolicLink(root.resolve("link.kt"), outside.resolve("Secret.kt"))
        Files.createSymbolicLink(root.resolve("linkdir"), outside.resolve("dir"))

        val index = RepoIndexer(listOf(root)).index(root)

        assertEquals(listOf("Real.kt"), index.paths())
        val names = index.files.flatMap { it.parsed.definitions }.map { it.name }
        assertFalse("OutsideSecret" in names)
        assertFalse("OutsideDeep" in names)
    }

    @Test
    fun `RM-8 a root that resolves outside every authorized root is rejected`() {
        val outside = tempRoot()
        outside.write("Secret.kt", "class OutsideSecret")
        val authorized = tempRoot()
        val sneaky = authorized.resolve("sneaky")
        Files.createSymbolicLink(sneaky, outside)

        assertFailsWith<IllegalArgumentException> { RepoIndexer(listOf(authorized)).index(sneaky) }
    }

    @Test
    fun `RM-8 roots outside authorization, missing, non-directory or with nothing authorized are rejected`() {
        val authorized = tempRoot()
        val other = tempRoot()
        val file = authorized.write("F.kt", "class F")

        assertFailsWith<IllegalArgumentException> { RepoIndexer(listOf(authorized)).index(other) }
        assertFailsWith<IllegalArgumentException> { RepoIndexer(listOf(authorized)).index(authorized.resolve("missing")) }
        assertFailsWith<IllegalArgumentException> { RepoIndexer(listOf(authorized)).index(file) }
        assertFailsWith<IllegalArgumentException> { RepoIndexer(emptyList()).index(authorized) }
    }

    @Test
    fun `RM-8 a dot-dot path cannot escape the authorized root`() {
        val outside = tempRoot()
        val authorized = tempRoot()
        assertFailsWith<IllegalArgumentException> { RepoIndexer(listOf(authorized)).index(authorized.resolve("..").resolve(outside.fileName)) }
    }

    @Test
    fun `RM-8 a subdirectory of an authorized root can be indexed with paths relative to it`() {
        val root = tempRoot()
        root.write("sub/Inner.kt", "class Inner")
        root.write("Outer.kt", "class Outer")

        assertEquals(listOf("Inner.kt"), RepoIndexer(listOf(root)).index(root.resolve("sub")).paths())
    }

    @Test
    fun `RM-8 the file cap truncates deterministically by path`() {
        val root = tempRoot()
        for (name in listOf("d.kt", "b.kt", "a.kt", "c.kt")) root.write(name, "class ${name.substringBefore('.').uppercase()}")

        val first = RepoIndexer(listOf(root), maxFiles = 2).index(root)
        val second = RepoIndexer(listOf(root), maxFiles = 2).index(root)

        assertEquals(listOf("a.kt", "b.kt"), first.paths())
        assertEquals(first.paths(), second.paths())
        assertTrue(first.truncated)
    }

    // ------------------------------------------------------------------ RM-9

    @Test
    fun `RM-9 a repeat index re-parses nothing`() {
        val root = tempRoot()
        root.write("A.kt", "class A")
        root.write("B.kt", "class B")
        val indexer = RepoIndexer(listOf(root))

        indexer.index(root)
        assertEquals(2, indexer.parsedFileCount)
        indexer.index(root)
        assertEquals(2, indexer.parsedFileCount)
    }

    @Test
    fun `RM-9 only changed files are re-parsed and the new content is seen`() {
        val root = tempRoot()
        val a = root.write("A.kt", "class A")
        root.write("B.kt", "class B")
        val indexer = RepoIndexer(listOf(root))
        indexer.index(root)

        a.writeText("class A\nclass AddedLater")
        Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 5_000))
        val index = indexer.index(root)

        assertEquals(3, indexer.parsedFileCount)
        val names = index.files.first { it.path == "A.kt" }.parsed.definitions.map { it.name }
        assertEquals(listOf("A", "AddedLater"), names)
    }

    @Test
    fun `RM-9 a same-size edit is noticed through the modification time`() {
        val root = tempRoot()
        val a = root.write("A.kt", "class A")
        val indexer = RepoIndexer(listOf(root))
        indexer.index(root)

        a.writeText("class B") // same length
        Files.setLastModifiedTime(a, FileTime.fromMillis(Files.getLastModifiedTime(a).toMillis() + 5_000))

        assertEquals(listOf("B"), indexer.index(root).files.single().parsed.definitions.map { it.name })
    }

    @Test
    fun `RM-9 deleted files disappear and added files appear`() {
        val root = tempRoot()
        val a = root.write("A.kt", "class A")
        root.write("B.kt", "class B")
        val indexer = RepoIndexer(listOf(root))
        assertEquals(listOf("A.kt", "B.kt"), indexer.index(root).paths())

        Files.delete(a)
        root.write("C.kt", "class C")

        assertEquals(listOf("B.kt", "C.kt"), indexer.index(root).paths())
    }

    @Test
    fun `RM-9 a skipped file is not re-read on every call`() {
        val root = tempRoot()
        root.resolve("Bad.kt").writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        val indexer = RepoIndexer(listOf(root))

        assertEquals(1, indexer.index(root).skipped)
        assertEquals(1, indexer.index(root).skipped)
        assertEquals(0, indexer.parsedFileCount)
    }
}
