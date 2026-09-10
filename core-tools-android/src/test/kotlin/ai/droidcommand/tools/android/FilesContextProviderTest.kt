package ai.droidcommand.tools.android

import ai.droidcommand.agent.ContextKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesContextProviderTest {
    @Test
    fun `a successful listing is formatted as one FILES contribution`() {
        val device = ScriptedDeviceController(
            listDirectoryResult = FileListResult.Success(
                listOf(
                    FileEntry("notes.txt", "/sdcard/notes.txt", isDirectory = false, sizeBytes = 42),
                    FileEntry("photos", "/sdcard/photos", isDirectory = true),
                ),
            ),
        )
        val provider = FilesContextProvider(device) { "/sdcard" }

        val contribution = provider.provide(null)!!

        assertEquals(ContextKind.FILES, contribution.kind)
        assertTrue(contribution.content.contains("notes.txt"))
        assertTrue(contribution.content.contains("[file]"))
        assertTrue(contribution.content.contains("42B"))
        assertTrue(contribution.content.contains("photos"))
        assertTrue(contribution.content.contains("[dir]"))
    }

    @Test
    fun `a failure is reported as unavailable with the real reason`() {
        val device = ScriptedDeviceController(listDirectoryResult = FileListResult.Failure("no device"))
        val provider = FilesContextProvider(device) { "/sdcard" }

        val contribution = provider.provide(null)!!

        assertTrue(contribution.content.contains("unavailable (no device)"))
    }

    @Test
    fun `against NullDeviceController the listing is honestly reported unavailable`() {
        val provider = FilesContextProvider(NullDeviceController()) { "/sdcard" }

        val contribution = provider.provide(null)!!

        assertTrue(contribution.content.contains("unavailable"))
        assertTrue(contribution.content.contains("no real device is connected"))
    }

    @Test
    fun `a null path contributes nothing`() {
        val provider = FilesContextProvider(NullDeviceController()) { null }
        assertNull(provider.provide(null))
    }

    @Test
    fun `an empty directory is reported as empty, not omitted`() {
        val device = ScriptedDeviceController(listDirectoryResult = FileListResult.Success(emptyList()))
        val provider = FilesContextProvider(device) { "/sdcard/empty" }

        val contribution = provider.provide(null)!!

        assertTrue(contribution.content.contains("(empty)"))
    }
}
