package ai.droidforge.tools.android

import ai.droidforge.agent.ToolResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TapElementToolTest {
    @Test
    fun `taps the center of the bounds of the matched element, not caller-supplied coordinates`() {
        val tree = UiTree(
            UiNode(
                className = "root",
                bounds = Rect(0, 0, 200, 200),
                children = listOf(UiNode(className = "android.widget.Button", text = "Save", resourceId = "btn_save", bounds = Rect(10, 20, 50, 60))),
            ),
            Instant.now(),
        )
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(tree), tapResult = DeviceActionResult.Success("tapped"))

        val result = TapElementTool(device).execute(mapOf("by" to "text", "value" to "Save"))

        assertIs<ToolResult.Success>(result)
        assertEquals(listOf(30 to 40), device.tapCalls) // center of (10,20)-(50,60)
    }

    @Test
    fun `fails without tapping when no element matches`() {
        val tree = UiTree(UiNode(bounds = Rect(0, 0, 10, 10)), Instant.now())
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(tree))

        val result = TapElementTool(device).execute(mapOf("by" to "text", "value" to "Nonexistent"))

        assertIs<ToolResult.Failure>(result)
        assertEquals(0, device.tapCalls.size)
    }

    @Test
    fun `taps the first match in tree order when multiple elements match`() {
        val first = UiNode(className = "android.widget.Button", bounds = Rect(0, 0, 10, 10))
        val second = UiNode(className = "android.widget.Button", bounds = Rect(100, 100, 110, 110))
        val tree = UiTree(UiNode(className = "root", bounds = Rect(0, 0, 200, 200), children = listOf(first, second)), Instant.now())
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(tree), tapResult = DeviceActionResult.Success("tapped"))

        TapElementTool(device).execute(mapOf("by" to "className", "value" to "android.widget.Button"))

        assertEquals(listOf(5 to 5), device.tapCalls)
    }

    @Test
    fun `surfaces the device's tap failure reason`() {
        val tree = UiTree(UiNode(text = "Save", bounds = Rect(0, 0, 10, 10)), Instant.now())
        val device = ScriptedDeviceController(uiTree = UiTreeResult.Success(tree), tapResult = DeviceActionResult.Failure("touch injection denied"))

        val result = assertIs<ToolResult.Failure>(TapElementTool(device).execute(mapOf("by" to "text", "value" to "Save")))
        assertEquals("touch injection denied", result.reason)
    }
}
