package ai.droidcommand.tools.android

import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileToolsTest {
    @Test
    fun `ReadFileTool delegates the given path and returns its content`() {
        val device = ScriptedDeviceController(readFileResult = FileReadResult.Success("hello"))
        val result = ReadFileTool(device).execute(mapOf("path" to "/sdcard/note.txt"))
        assertIs<ToolResult.Success>(result)
        assertEquals("hello", result.output)
        assertEquals(listOf("/sdcard/note.txt"), device.readFileCalls)
    }

    @Test
    fun `ReadFileTool fails without calling the device when path is missing`() {
        val device = ScriptedDeviceController()
        val result = ReadFileTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.readFileCalls.size)
    }

    @Test
    fun `ReadFileTool surfaces a device failure`() {
        val device = ScriptedDeviceController(readFileResult = FileReadResult.Failure("not found"))
        assertIs<ToolResult.Failure>(ReadFileTool(device).execute(mapOf("path" to "/sdcard/missing.txt")))
    }

    @Test
    fun `WriteFileTool delegates path, content, and defaults append to false`() {
        val device = ScriptedDeviceController(writeFileResult = DeviceActionResult.Success("written"))
        val result = WriteFileTool(device).execute(mapOf("path" to "/sdcard/note.txt", "content" to "hi"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf(Triple("/sdcard/note.txt", "hi", false)), device.writeFileCalls)
    }

    @Test
    fun `WriteFileTool parses an explicit append flag`() {
        val device = ScriptedDeviceController(writeFileResult = DeviceActionResult.Success("written"))
        WriteFileTool(device).execute(mapOf("path" to "/sdcard/note.txt", "content" to "hi", "append" to "true"))
        assertEquals(listOf(Triple("/sdcard/note.txt", "hi", true)), device.writeFileCalls)
    }

    @Test
    fun `WriteFileTool fails without calling the device when content is missing`() {
        val device = ScriptedDeviceController()
        val result = WriteFileTool(device).execute(mapOf("path" to "/sdcard/note.txt"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.writeFileCalls.size)
    }

    @Test
    fun `MoveFileTool delegates fromPath and toPath`() {
        val device = ScriptedDeviceController(moveFileResult = DeviceActionResult.Success("moved"))
        val result = MoveFileTool(device).execute(mapOf("fromPath" to "/a", "toPath" to "/b"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("/a" to "/b"), device.moveFileCalls)
    }

    @Test
    fun `MoveFileTool fails without calling the device when toPath is missing`() {
        val device = ScriptedDeviceController()
        val result = MoveFileTool(device).execute(mapOf("fromPath" to "/a"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.moveFileCalls.size)
    }

    @Test
    fun `CopyFileTool delegates fromPath and toPath`() {
        val device = ScriptedDeviceController(copyFileResult = DeviceActionResult.Success("copied"))
        val result = CopyFileTool(device).execute(mapOf("fromPath" to "/a", "toPath" to "/b"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("/a" to "/b"), device.copyFileCalls)
    }

    @Test
    fun `CopyFileTool fails without calling the device when fromPath is missing`() {
        val device = ScriptedDeviceController()
        val result = CopyFileTool(device).execute(mapOf("toPath" to "/b"))
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.copyFileCalls.size)
    }

    @Test
    fun `DeleteFileTool delegates the given path`() {
        val device = ScriptedDeviceController(deleteFileResult = DeviceActionResult.Success("deleted"))
        val result = DeleteFileTool(device).execute(mapOf("path" to "/sdcard/note.txt"))
        assertIs<ToolResult.Success>(result)
        assertEquals(listOf("/sdcard/note.txt"), device.deleteFileCalls)
    }

    @Test
    fun `DeleteFileTool fails without calling the device when path is missing`() {
        val device = ScriptedDeviceController()
        val result = DeleteFileTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.deleteFileCalls.size)
    }

    @Test
    fun `ListDirectoryTool formats each entry on its own line`() {
        val device = ScriptedDeviceController(
            listDirectoryResult = FileListResult.Success(
                listOf(
                    FileEntry("notes", "/sdcard/notes", isDirectory = true),
                    FileEntry("a.txt", "/sdcard/a.txt", isDirectory = false, sizeBytes = 42),
                ),
            ),
        )
        val result = assertIs<ToolResult.Success>(ListDirectoryTool(device).execute(mapOf("path" to "/sdcard")))
        assertEquals(2, result.output.lines().size)
        assertEquals(listOf("/sdcard"), device.listDirectoryCalls)
    }

    @Test
    fun `ListDirectoryTool fails without calling the device when path is missing`() {
        val device = ScriptedDeviceController()
        val result = ListDirectoryTool(device).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.listDirectoryCalls.size)
    }

    @Test
    fun `ListDirectoryTool surfaces a device failure`() {
        val device = ScriptedDeviceController(listDirectoryResult = FileListResult.Failure("no device"))
        assertIs<ToolResult.Failure>(ListDirectoryTool(device).execute(mapOf("path" to "/sdcard")))
    }
}
