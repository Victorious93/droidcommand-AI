package ai.droidcommand.codeedit

import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchReplaceEditorTest {
    @Test
    fun `applies a single matching block and writes the file`() {
        val root = createTempDirectory("edit-test")
        val file = root.resolve("Foo.kt")
        file.writeText("fun greet() = \"hello\"\n")

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(file, listOf(EditBlock("\"hello\"", "\"goodbye\"")))

        assertIs<EditResult.Applied>(result)
        assertEquals(1, result.blocksApplied)
        assertEquals("fun greet() = \"goodbye\"\n", file.readText())
    }

    @Test
    fun `applies multiple blocks in one batch`() {
        val root = createTempDirectory("edit-test")
        val file = root.resolve("Foo.kt")
        file.writeText("val a = 1\nval b = 2\n")

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(
            file,
            listOf(EditBlock("val a = 1", "val a = 10"), EditBlock("val b = 2", "val b = 20")),
        )

        assertIs<EditResult.Applied>(result)
        assertEquals("val a = 10\nval b = 20\n", file.readText())
    }

    @Test
    fun `rejects and leaves the file untouched when search text is not found`() {
        val root = createTempDirectory("edit-test")
        val file = root.resolve("Foo.kt")
        val original = "val a = 1\n"
        file.writeText(original)

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(file, listOf(EditBlock("val a = 999", "val a = 2")))

        assertIs<EditResult.Rejected>(result)
        assertEquals(original, file.readText())
    }

    @Test
    fun `rejects an ambiguous match and leaves the file untouched`() {
        val root = createTempDirectory("edit-test")
        val file = root.resolve("Foo.kt")
        val original = "dup\ndup\n"
        file.writeText(original)

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(file, listOf(EditBlock("dup", "single")))

        assertIs<EditResult.Rejected>(result)
        assertEquals(original, file.readText())
    }

    @Test
    fun `a partial-batch failure leaves the file fully untouched`() {
        val root = createTempDirectory("edit-test")
        val file = root.resolve("Foo.kt")
        val original = "val a = 1\nval b = 2\n"
        file.writeText(original)

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(
            file,
            listOf(EditBlock("val a = 1", "val a = 10"), EditBlock("val c = 999", "val c = 3")),
        )

        assertIs<EditResult.Rejected>(result)
        assertEquals(original, file.readText())
    }

    @Test
    fun `rejects a target outside every authorized root`() {
        val root = createTempDirectory("edit-test")
        val outside = createTempDirectory("edit-test-outside")
        val file = outside.resolve("Foo.kt")
        file.writeText("val a = 1\n")

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(file, listOf(EditBlock("val a = 1", "val a = 2")))

        assertIs<EditResult.Rejected>(result)
        assertEquals("val a = 1\n", file.readText())
    }

    @Test
    fun `rejects a target that does not exist`() {
        val root = createTempDirectory("edit-test")
        val missing = root.resolve("DoesNotExist.kt")

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(missing, listOf(EditBlock("x", "y")))

        assertIs<EditResult.Rejected>(result)
    }

    @Test
    fun `an empty authorized-root list rejects every target`() {
        val root = createTempDirectory("edit-test")
        val file = root.resolve("Foo.kt")
        file.writeText("val a = 1\n")

        val editor = SearchReplaceEditor(emptyList())
        val result = editor.apply(file, listOf(EditBlock("val a = 1", "val a = 2")))

        assertIs<EditResult.Rejected>(result)
    }

    @Test
    fun `rejects an empty block list`() {
        val root = createTempDirectory("edit-test")
        val file = root.resolve("Foo.kt")
        file.writeText("val a = 1\n")

        val editor = SearchReplaceEditor(listOf(root))
        val result = editor.apply(file, emptyList())

        assertIs<EditResult.Rejected>(result)
    }
}
