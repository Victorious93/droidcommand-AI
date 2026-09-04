package ai.droidforge.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkspaceManagerTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("droidforge-workspace-manager-test")
    }

    @AfterTest
    fun tearDown() {
        deleteRecursively(root)
    }

    @Test
    fun `create makes a real directory under the authorized root`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        assertTrue(Files.exists(Path.of(handle.rootPath)))
        assertTrue(Path.of(handle.rootPath).startsWith(root))
    }

    @Test
    fun `create rejects a root that was not authorized`() {
        val unauthorized = Files.createTempDirectory("droidforge-workspace-manager-unauthorized")
        try {
            val manager = WorkspaceManager(listOf(root))
            assertFailsWith<IllegalArgumentException> {
                manager.create(unauthorized)
            }
        } finally {
            deleteRecursively(unauthorized)
        }
    }

    @Test
    fun `each created workspace is isolated with a distinct id and directory`() {
        val manager = WorkspaceManager(listOf(root))
        val a = manager.create()
        val b = manager.create()
        assertNotEquals(a.workspaceId, b.workspaceId)
        assertNotEquals(a.rootPath, b.rootPath)
    }

    @Test
    fun `importSource copies real file content from source into the workspace`() {
        val sourceDir = Files.createTempDirectory("droidforge-workspace-manager-source")
        try {
            Files.writeString(sourceDir.resolve("hello.txt"), "hello world")
            Files.createDirectories(sourceDir.resolve("nested"))
            Files.writeString(sourceDir.resolve("nested/inner.txt"), "nested content")

            val manager = WorkspaceManager(listOf(root))
            val handle = manager.create()
            val result = manager.importSource(handle, SourceLocation.LocalDirectory(sourceDir.toString()))

            val success = assertIs<WorkspaceImportResult.Success>(result)
            assertEquals("hello world", Files.readString(Path.of(success.sourceDir).resolve("hello.txt")))
            assertEquals("nested content", Files.readString(Path.of(success.sourceDir).resolve("nested/inner.txt")))
        } finally {
            deleteRecursively(sourceDir)
        }
    }

    @Test
    fun `importSource fails cleanly when the source path does not exist`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        val result = manager.importSource(handle, SourceLocation.LocalDirectory(root.resolve("does-not-exist").toString()))
        assertIs<WorkspaceImportResult.Failure>(result)
        assertEquals(WorkspaceState.FAILED, handle.state)
    }

    @Test
    fun `clean removes the workspace directory from disk`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        manager.clean(handle)
        assertFalse(Files.exists(Path.of(handle.rootPath)))
    }

    @Test
    fun `clean never touches files outside the workspace root`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        val sibling = root.resolve("sibling-file.txt")
        Files.writeString(sibling, "must survive")

        manager.clean(handle)

        assertTrue(Files.exists(sibling))
        assertEquals("must survive", Files.readString(sibling))
    }

    @Test
    fun `workspace lifecycle progresses through the expected valid sequence`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        assertEquals(WorkspaceState.CREATED, handle.state)

        handle.transition(WorkspaceState.PREPARING)
        handle.transition(WorkspaceState.READY)
        handle.transition(WorkspaceState.BUILDING)
        handle.transition(WorkspaceState.COMPLETED)
        handle.transition(WorkspaceState.CLEANING)
        handle.transition(WorkspaceState.CLEANED)

        assertEquals(WorkspaceState.CLEANED, handle.state)
    }

    @Test
    fun `an illegal transition throws instead of silently succeeding`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        assertFailsWith<IllegalWorkspaceTransition> {
            handle.transition(WorkspaceState.BUILDING)
        }
    }

    @Test
    fun `CLEANED is a terminal state`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        handle.transition(WorkspaceState.CLEANING)
        handle.transition(WorkspaceState.CLEANED)
        assertFailsWith<IllegalWorkspaceTransition> {
            handle.transition(WorkspaceState.PREPARING)
        }
    }

    @Test
    fun `a workspace cancelled right after creation can be cleaned directly`() {
        val manager = WorkspaceManager(listOf(root))
        val handle = manager.create()
        assertEquals(WorkspaceState.CREATED, handle.state)
        manager.clean(handle)
        assertEquals(WorkspaceState.CLEANED, handle.state)
    }
}
