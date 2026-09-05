package ai.droidcommand.tools.android

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun node(
    className: String? = null,
    text: String? = null,
    contentDescription: String? = null,
    resourceId: String? = null,
    children: List<UiNode> = emptyList(),
) = UiNode(className = className, text = text, contentDescription = contentDescription, resourceId = resourceId, bounds = Rect(0, 0, 10, 10), children = children)

private fun sampleTree(): UiTree {
    val saveButton = node(className = "android.widget.Button", text = "Save", resourceId = "btn_save")
    val cancelButton = node(className = "android.widget.Button", text = "Cancel", resourceId = "btn_cancel")
    val title = node(className = "android.widget.TextView", text = "Settings", contentDescription = "Screen title")
    val root = node(className = "android.widget.LinearLayout", children = listOf(title, saveButton, cancelButton))
    return UiTree(root, Instant.now())
}

class UiTreeQueryTest {
    @Test
    fun `finds a node by exact text`() {
        val matches = sampleTree().findAll(Selector.ByText("Save"))
        assertEquals(1, matches.size)
        assertEquals("btn_save", matches.single().resourceId)
    }

    @Test
    fun `exact text does not match a substring`() {
        assertTrue(sampleTree().findAll(Selector.ByText("Sav")).isEmpty())
    }

    @Test
    fun `non-exact text matches a case-insensitive substring`() {
        val matches = sampleTree().findAll(Selector.ByText("sav", exact = false))
        assertEquals(1, matches.size)
    }

    @Test
    fun `finds a node by resourceId`() {
        val matches = sampleTree().findAll(Selector.ByResourceId("btn_cancel"))
        assertEquals("Cancel", matches.single().text)
    }

    @Test
    fun `finds a node by contentDescription`() {
        val matches = sampleTree().findAll(Selector.ByContentDescription("Screen title"))
        assertEquals("Settings", matches.single().text)
    }

    @Test
    fun `finds nodes by className, including multiple matches`() {
        val matches = sampleTree().findAll(Selector.ByClassName("android.widget.Button"))
        assertEquals(2, matches.size)
    }

    @Test
    fun `And requires every sub-selector to match`() {
        val matches = sampleTree().findAll(Selector.And(listOf(Selector.ByClassName("android.widget.Button"), Selector.ByText("Save"))))
        assertEquals(1, matches.size)
        assertEquals("btn_save", matches.single().resourceId)
    }

    @Test
    fun `And with a contradictory combination matches nothing`() {
        val matches = sampleTree().findAll(Selector.And(listOf(Selector.ByText("Save"), Selector.ByText("Cancel"))))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `findFirst returns the first match in pre-order tree order`() {
        val first = sampleTree().findFirst(Selector.ByClassName("android.widget.Button"))
        assertEquals("Save", first?.text)
    }

    @Test
    fun `findFirst returns null when nothing matches`() {
        assertNull(sampleTree().findFirst(Selector.ByText("Does not exist")))
    }

    @Test
    fun `traverses nested children, not just direct children of the root`() {
        val deeplyNested = node(text = "Deep", resourceId = "deep")
        val middle = node(className = "group", children = listOf(deeplyNested))
        val root = node(className = "root", children = listOf(middle))
        val tree = UiTree(root, Instant.now())

        val match = tree.findFirst(Selector.ByResourceId("deep"))
        assertEquals("Deep", match?.text)
    }
}
