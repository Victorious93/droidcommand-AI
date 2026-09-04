package ai.droidforge.build

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkspacePathValidatorTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("droidforge-path-validator-test")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(root)
    }

    @Test
    fun `resolves a normal relative path inside the root`() {
        val validator = WorkspacePathValidator(listOf(root))
        val resolved = validator.resolve(root, "build/output.txt")
        assertTrue(resolved.startsWith(root))
        assertEquals(root.resolve("build/output.txt").normalize(), resolved)
    }

    @Test
    fun `rejects a simple traversal attempt`() {
        val validator = WorkspacePathValidator(listOf(root))
        assertFailsWith<PathSecurityViolation> {
            validator.resolve(root, "../escaped")
        }
    }

    @Test
    fun `rejects an absolute path escape`() {
        val validator = WorkspacePathValidator(listOf(root))
        assertFailsWith<PathSecurityViolation> {
            validator.resolve(root, "/etc/passwd")
        }
    }

    @Test
    fun `rejects a nested traversal that normalizes outside the root`() {
        val validator = WorkspacePathValidator(listOf(root))
        assertFailsWith<PathSecurityViolation> {
            validator.resolve(root, "subdir/../../escaped")
        }
    }

    @Test
    fun `accepts deeply nested subdirectories`() {
        val validator = WorkspacePathValidator(listOf(root))
        val resolved = validator.resolve(root, "a/b/c/d.txt")
        assertTrue(resolved.startsWith(root))
    }

    @Test
    fun `rejects when the given root itself is not an authorized root`() {
        val otherRoot = Files.createTempDirectory("droidforge-path-validator-unauthorized")
        try {
            val validator = WorkspacePathValidator(listOf(root))
            assertFailsWith<IllegalArgumentException> {
                validator.resolve(otherRoot, "file.txt")
            }
        } finally {
            deleteRecursively(otherRoot)
        }
    }
}

internal fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
