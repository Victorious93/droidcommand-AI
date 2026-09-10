package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun node(id: String, type: EntityType = EntityType.NOTE, label: String = id) = GraphNode(id, type, label)

private fun edge(id: String, fromId: String, toId: String, relationship: String = "relates_to") =
    GraphEdge(id, fromId, toId, relationship)

class InMemoryKnowledgeGraphStoreTest {
    @Test
    fun `save then load returns an equal node`() {
        val store = InMemoryKnowledgeGraphStore()
        val n = node("a", EntityType.TASK, "Do the thing")

        store.saveNode(n)

        assertEquals(n, store.loadNode("a"))
    }

    @Test
    fun `load of an unsaved node id returns null`() {
        assertNull(InMemoryKnowledgeGraphStore().loadNode("nope"))
    }

    @Test
    fun `listNodes returns every saved id, sorted`() {
        val store = InMemoryKnowledgeGraphStore()
        store.saveNode(node("zeta"))
        store.saveNode(node("alpha"))

        assertEquals(listOf("alpha", "zeta"), store.listNodes())
    }

    @Test
    fun `listNodes with a type filter returns only matching nodes`() {
        val store = InMemoryKnowledgeGraphStore()
        store.saveNode(node("t1", EntityType.TASK))
        store.saveNode(node("d1", EntityType.DEVICE))

        assertEquals(listOf("t1"), store.listNodes(EntityType.TASK))
    }

    @Test
    fun `save then load returns an equal edge`() {
        val store = InMemoryKnowledgeGraphStore()
        val e = edge("e1", "a", "b", "depends_on")

        store.saveEdge(e)

        assertEquals(e, store.loadEdge("e1"))
    }

    @Test
    fun `edges filters by direction`() {
        val store = InMemoryKnowledgeGraphStore()
        store.saveEdge(edge("out", "a", "b"))
        store.saveEdge(edge("in", "b", "a"))

        assertEquals(listOf("out"), store.edges("a", direction = EdgeDirection.OUTGOING).map { it.id })
        assertEquals(listOf("in"), store.edges("a", direction = EdgeDirection.INCOMING).map { it.id })
        assertEquals(listOf("in", "out"), store.edges("a", direction = EdgeDirection.BOTH).map { it.id }.sorted())
    }

    @Test
    fun `edges filters by relationship`() {
        val store = InMemoryKnowledgeGraphStore()
        store.saveEdge(edge("e1", "a", "b", "depends_on"))
        store.saveEdge(edge("e2", "a", "c", "references"))

        assertEquals(listOf("e1"), store.edges("a", relationship = "depends_on").map { it.id })
    }

    @Test
    fun `deleting a node with cascade true removes every touching edge`() {
        val store = InMemoryKnowledgeGraphStore()
        store.saveNode(node("a"))
        store.saveNode(node("b"))
        store.saveEdge(edge("e1", "a", "b"))
        store.saveEdge(edge("e2", "b", "a"))

        assertTrue(store.deleteNode("a", cascade = true))

        assertNull(store.loadEdge("e1"))
        assertNull(store.loadEdge("e2"))
    }

    @Test
    fun `deleting a node with cascade false leaves edges in place, honestly dangling`() {
        val store = InMemoryKnowledgeGraphStore()
        store.saveNode(node("a"))
        store.saveNode(node("b"))
        store.saveEdge(edge("e1", "a", "b"))

        assertTrue(store.deleteNode("a", cascade = false))

        assertEquals(edge("e1", "a", "b"), store.loadEdge("e1"))
        assertEquals(emptyList(), store.neighbors("b").map { it.id })
    }

    @Test
    fun `delete of an unknown node returns false`() {
        assertFalse(InMemoryKnowledgeGraphStore().deleteNode("nope"))
    }

    @Test
    fun `delete of an unknown edge returns false`() {
        assertFalse(InMemoryKnowledgeGraphStore().deleteEdge("nope"))
    }
}
