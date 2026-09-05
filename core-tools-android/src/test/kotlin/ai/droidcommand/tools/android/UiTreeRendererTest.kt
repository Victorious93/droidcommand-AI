package ai.droidcommand.tools.android

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class UiTreeRendererTest {
    @Test
    fun `renders className, text, and resourceId`() {
        val root = UiNode(className = "android.widget.Button", text = "Save", resourceId = "btn_save", bounds = Rect(1, 2, 3, 4))
        val rendered = renderUiTree(UiTree(root, Instant.now()))
        assertTrue(rendered.contains("android.widget.Button"))
        assertTrue(rendered.contains("text=\"Save\""))
        assertTrue(rendered.contains("id=btn_save"))
    }

    @Test
    fun `marks clickable and disabled nodes`() {
        val root = UiNode(bounds = Rect(0, 0, 1, 1), clickable = true, enabled = false)
        val rendered = renderUiTree(UiTree(root, Instant.now()))
        assertTrue(rendered.contains("[clickable]"))
        assertTrue(rendered.contains("[disabled]"))
    }

    @Test
    fun `indents nested children more deeply than their parent`() {
        val child = UiNode(text = "child", bounds = Rect(0, 0, 1, 1))
        val root = UiNode(text = "root", bounds = Rect(0, 0, 1, 1), children = listOf(child))
        val rendered = renderUiTree(UiTree(root, Instant.now()))
        val lines = rendered.lines()
        val rootLine = lines.first { it.contains("root") }
        val childLine = lines.first { it.contains("child") }
        val rootIndent = rootLine.takeWhile { it == ' ' }.length
        val childIndent = childLine.takeWhile { it == ' ' }.length
        assertTrue(childIndent > rootIndent)
    }
}
