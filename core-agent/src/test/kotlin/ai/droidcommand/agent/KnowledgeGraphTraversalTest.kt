package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun node(id: String) = GraphNode(id, EntityType.CONCEPT, id)

private fun edge(id: String, fromId: String, toId: String, relationship: String = "relates_to") =
    GraphEdge(id, fromId, toId, relationship)

class KnowledgeGraphTraversalTest {
    @Test
    fun `neighbors returns every node one hop away, regardless of edge direction`() {
        val store = InMemoryKnowledgeGraphStore()
        listOf("a", "b", "c").forEach { store.saveNode(node(it)) }
        store.saveEdge(edge("e1", "a", "b"))
        store.saveEdge(edge("e2", "c", "a"))

        val result = store.neighbors("a").map { it.id }.sorted()

        assertEquals(listOf("b", "c"), result)
    }

    @Test
    fun `neighbors can be filtered by relationship`() {
        val store = InMemoryKnowledgeGraphStore()
        listOf("a", "b", "c").forEach { store.saveNode(node(it)) }
        store.saveEdge(edge("e1", "a", "b", "depends_on"))
        store.saveEdge(edge("e2", "a", "c", "references"))

        assertEquals(listOf("b"), store.neighbors("a", relationship = "depends_on").map { it.id })
    }

    @Test
    fun `relatedWithinHops on a line graph excludes nodes beyond maxHops`() {
        // a -> b -> c -> d
        val store = InMemoryKnowledgeGraphStore()
        listOf("a", "b", "c", "d").forEach { store.saveNode(node(it)) }
        store.saveEdge(edge("ab", "a", "b"))
        store.saveEdge(edge("bc", "b", "c"))
        store.saveEdge(edge("cd", "c", "d"))

        assertEquals(listOf("b"), store.relatedWithinHops("a", maxHops = 1).map { it.id })
        assertEquals(listOf("b", "c"), store.relatedWithinHops("a", maxHops = 2).map { it.id }.sorted())
        assertEquals(listOf("b", "c", "d"), store.relatedWithinHops("a", maxHops = 3).map { it.id }.sorted())
        assertEquals(listOf("b", "c", "d"), store.relatedWithinHops("a", maxHops = 100).map { it.id }.sorted())
    }

    @Test
    fun `relatedWithinHops on a star graph returns every spoke at one hop`() {
        val store = InMemoryKnowledgeGraphStore()
        listOf("hub", "s1", "s2", "s3").forEach { store.saveNode(node(it)) }
        store.saveEdge(edge("e1", "hub", "s1"))
        store.saveEdge(edge("e2", "hub", "s2"))
        store.saveEdge(edge("e3", "hub", "s3"))

        assertEquals(listOf("s1", "s2", "s3"), store.relatedWithinHops("hub", maxHops = 1).map { it.id }.sorted())
    }

    @Test
    fun `relatedWithinHops on a cycle never revisits or duplicates a node`() {
        // a -> b -> c -> a
        val store = InMemoryKnowledgeGraphStore()
        listOf("a", "b", "c").forEach { store.saveNode(node(it)) }
        store.saveEdge(edge("ab", "a", "b"))
        store.saveEdge(edge("bc", "b", "c"))
        store.saveEdge(edge("ca", "c", "a"))

        val result = store.relatedWithinHops("a", maxHops = 10)

        assertEquals(listOf("b", "c"), result.map { it.id }.sorted())
        assertEquals(result.size, result.distinctBy { it.id }.size)
    }

    @Test
    fun `relatedWithinHops only follows matching relationships`() {
        val store = InMemoryKnowledgeGraphStore()
        listOf("a", "b", "c").forEach { store.saveNode(node(it)) }
        store.saveEdge(edge("ab", "a", "b", "depends_on"))
        store.saveEdge(edge("bc", "b", "c", "references"))

        val result = store.relatedWithinHops("a", maxHops = 5, relationship = "depends_on")

        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun `relatedWithinHops rejects a non-positive maxHops`() {
        assertFailsWith<IllegalArgumentException> { InMemoryKnowledgeGraphStore().relatedWithinHops("a", maxHops = 0) }
    }
}
