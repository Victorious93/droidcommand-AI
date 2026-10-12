package ai.droidcommand.app.automation

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutomationKeyRegistryTest {
    private fun newDir(): File = Files.createTempDirectory("automation-registry-test").toFile()

    private val first = AutomationKey("aaaa", "First", AutomationAction.Shell("ls", ""))
    private val second = AutomationKey("bbbb", "Second", AutomationAction.Ai("openai", "hello"))

    @Test
    fun `save then load returns the same keys in the same order`() {
        val dir = newDir()
        try {
            AutomationKeyRegistry(dir).save(listOf(first, second))
            assertEquals(listOf(first, second), AutomationKeyRegistry(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing registry loads as empty`() {
        val dir = newDir()
        try {
            assertEquals(emptyList(), AutomationKeyRegistry(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `malformed lines are skipped and the good records survive`() {
        val dir = newDir()
        try {
            File(dir, AutomationKeyRegistry.FILE_NAME).writeText("garbage\n\n" + AutomationKeyCodec.encode(first) + "\nmore garbage\n")
            assertEquals(listOf(first), AutomationKeyRegistry(dir).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `saving leaves no temporary file behind and replaces the previous list`() {
        val dir = newDir()
        try {
            val registry = AutomationKeyRegistry(dir)
            registry.save(listOf(first, second))
            registry.save(listOf(second))
            assertFalse(File(dir, "${AutomationKeyRegistry.FILE_NAME}.tmp").exists())
            assertTrue(File(dir, AutomationKeyRegistry.FILE_NAME).isFile)
            assertEquals(listOf(second), registry.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
