package ai.droidcommand.agent

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileKnowledgeGraphStoreTest {
    private fun newStore() = JsonFileKnowledgeGraphStore(Files.createTempDirectory("droidcommand-graph-store-test"))

    private fun node(id: String, type: EntityType = EntityType.NOTE) =
        GraphNode(id, type, "Label for $id", properties = mapOf("key" to "value"))

    private fun edge(id: String, fromId: String, toId: String) =
        GraphEdge(id, fromId, toId, "relates_to", properties = mapOf("weight" to "1"))

    @Test
    fun `save then load returns an equal node`() {
        val store = newStore()
        val n = node("a", EntityType.TASK)

        store.saveNode(n)

        assertEquals(n, store.loadNode("a"))
    }

    @Test
    fun `save then load returns an equal edge`() {
        val store = newStore()
        val e = edge("e1", "a", "b")

        store.saveEdge(e)

        assertEquals(e, store.loadEdge("e1"))
    }

    @Test
    fun `a node and an edge survive being loaded from a fresh store instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-graph-store-test")
        JsonFileKnowledgeGraphStore(dir).apply {
            saveNode(node("a"))
            saveEdge(edge("e1", "a", "b"))
        }

        val reopened = JsonFileKnowledgeGraphStore(dir)

        assertEquals(node("a"), reopened.loadNode("a"))
        assertEquals(edge("e1", "a", "b"), reopened.loadEdge("e1"))
    }

    @Test
    fun `load of an unsaved node or edge id returns null`() {
        val store = newStore()
        assertNull(store.loadNode("nope"))
        assertNull(store.loadEdge("nope"))
    }

    @Test
    fun `listNodes returns every saved id, sorted, optionally filtered by type`() {
        val store = newStore()
        store.saveNode(node("zeta", EntityType.TASK))
        store.saveNode(node("alpha", EntityType.DEVICE))

        assertEquals(listOf("alpha", "zeta"), store.listNodes())
        assertEquals(listOf("zeta"), store.listNodes(EntityType.TASK))
    }

    @Test
    fun `edges genuinely re-reads from a freshly reopened store, not a cache`() {
        val dir = Files.createTempDirectory("droidcommand-graph-store-test")
        JsonFileKnowledgeGraphStore(dir).apply {
            saveNode(node("a"))
            saveNode(node("b"))
            saveEdge(edge("e1", "a", "b"))
        }

        val reopened = JsonFileKnowledgeGraphStore(dir)

        assertEquals(listOf("e1"), reopened.edges("a").map { it.id })
    }

    @Test
    fun `deleteNode with cascade removes touching edges, surviving a fresh store instance`() {
        val dir = Files.createTempDirectory("droidcommand-graph-store-test")
        JsonFileKnowledgeGraphStore(dir).apply {
            saveNode(node("a"))
            saveNode(node("b"))
            saveEdge(edge("e1", "a", "b"))
        }

        JsonFileKnowledgeGraphStore(dir).deleteNode("a", cascade = true)

        val reopened = JsonFileKnowledgeGraphStore(dir)
        assertNull(reopened.loadNode("a"))
        assertNull(reopened.loadEdge("e1"))
    }

    @Test
    fun `delete removes the file and reports whether it existed`() {
        val store = newStore()
        store.saveNode(node("a"))
        store.saveEdge(edge("e1", "a", "b"))

        assertTrue(store.deleteNode("a", cascade = false))
        assertTrue(store.deleteEdge("e1"))
        assertEquals(false, store.deleteNode("a"))
        assertEquals(false, store.deleteEdge("e1"))
    }

    @Test
    fun `a node id containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidGraphNodeId> { store.saveNode(node("../escape")) }
        assertFailsWith<InvalidGraphNodeId> { store.loadNode("../escape") }
        assertFailsWith<InvalidGraphNodeId> { store.deleteNode("../escape") }
    }

    @Test
    fun `an edge id containing a path separator is rejected rather than escaping the directory`() {
        val store = newStore()
        assertFailsWith<InvalidGraphEdgeId> { store.saveEdge(edge("../escape", "a", "b")) }
        assertFailsWith<InvalidGraphEdgeId> { store.loadEdge("../escape") }
        assertFailsWith<InvalidGraphEdgeId> { store.deleteEdge("../escape") }
    }

    @Test
    fun `an absolute-path-shaped id is rejected for both nodes and edges`() {
        val store = newStore()
        assertFailsWith<InvalidGraphNodeId> { store.saveNode(node("/etc/passwd")) }
        assertFailsWith<InvalidGraphEdgeId> { store.saveEdge(edge("/etc/passwd", "a", "b")) }
    }
}
