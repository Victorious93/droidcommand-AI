package ai.droidcommand.tools.android

import ai.droidcommand.agent.ToolResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun treeWithSaveButton() = UiTree(
    UiNode(
        className = "root",
        bounds = Rect(0, 0, 100, 100),
        children = listOf(UiNode(className = "android.widget.Button", text = "Save", resourceId = "btn_save", bounds = Rect(10, 10, 50, 30))),
    ),
    Instant.now(),
)

class FindElementToolTest {
    @Test
    fun `finds an element by text and reports its bounds`() {
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(treeWithSaveButton()))
        val result = assertIs<ToolResult.Success>(FindElementTool(device).execute(mapOf("by" to "text", "value" to "Save")))
        assertEquals(true, result.output.contains("10,10,50,30"))
    }

    @Test
    fun `fails when no element matches`() {
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(treeWithSaveButton()))
        assertIs<ToolResult.Failure>(FindElementTool(device).execute(mapOf("by" to "text", "value" to "Nonexistent")))
    }

    @Test
    fun `fails without querying the device when 'by' is missing`() {
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(treeWithSaveButton()))
        FindElementTool(device).execute(mapOf("value" to "Save"))
        assertEquals(0, device.getUiTreeCalls)
    }

    @Test
    fun `fails for an unknown selector type`() {
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(treeWithSaveButton()))
        assertIs<ToolResult.Failure>(FindElementTool(device).execute(mapOf("by" to "bogus", "value" to "x")))
    }

    @Test
    fun `surfaces a device failure when the UI tree cannot be read`() {
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Failure("no device"))
        assertIs<ToolResult.Failure>(FindElementTool(device).execute(mapOf("by" to "text", "value" to "Save")))
    }
}
