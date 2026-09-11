package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InMemoryKnowledgeGraphTest {
    private fun entity(id: String, type: EntityType = EntityType.NOTE, label: String = id) = Entity(id, type, label)

    @Test
    fun `addEntity then getEntity returns an equal entity`() {
        val graph = InMemoryKnowledgeGraph()
        val e = entity("device-1", EntityType.DEVICE, "Pixel 9")

        graph.addEntity(e)

        assertEquals(e, graph.getEntity("device-1"))
    }

    @Test
    fun `getEntity of an unsaved id returns null`() {
        assertNull(InMemoryKnowledgeGraph().getEntity("nope"))
    }

    @Test
    fun `entitiesByType returns only entities of that type, sorted by id`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("b", EntityType.DEVICE))
        graph.addEntity(entity("a", EntityType.DEVICE))
        graph.addEntity(entity("c", EntityType.NOTE))

        assertEquals(listOf("a", "b"), graph.entitiesByType(EntityType.DEVICE).map { it.id })
    }

    @Test
    fun `searchEntities matches a literal substring of label case-insensitively`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a", label = "Pixel 9 Pro"))

        assertEquals(listOf("a"), graph.searchEntities("pixel").map { it.id })
    }

    @Test
    fun `searchEntities does not match on meaning, only literal substrings`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a", label = "Pixel 9 Pro"))

        assertEquals(emptyList(), graph.searchEntities("smartphone"))
    }

    @Test
    fun `addRelationship succeeds when both endpoints exist`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))

        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))

        assertEquals(listOf("r1"), graph.relationshipsFrom("a").map { it.id })
    }

    @Test
    fun `addRelationship throws UnknownEntityException for a missing fromId`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("b"))

        val e = assertFailsWith<UnknownEntityException> { graph.addRelationship(Relationship("r1", "a", "b", "relates_to")) }
        assertEquals("a", e.id)
    }

    @Test
    fun `addRelationship throws UnknownEntityException for a missing toId`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))

        val e = assertFailsWith<UnknownEntityException> { graph.addRelationship(Relationship("r1", "a", "b", "relates_to")) }
        assertEquals("b", e.id)
    }

    @Test
    fun `relationshipsFrom and relationshipsTo distinguish direction`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))

        assertEquals(listOf("r1"), graph.relationshipsFrom("a").map { it.id })
        assertEquals(emptyList(), graph.relationshipsFrom("b"))
        assertEquals(listOf("r1"), graph.relationshipsTo("b").map { it.id })
        assertEquals(emptyList(), graph.relationshipsTo("a"))
    }

    @Test
    fun `removeEntity cascades to every relationship touching it, either direction`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addEntity(entity("c"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))
        graph.addRelationship(Relationship("r2", "c", "a", "relates_to"))

        assertTrue(graph.removeEntity("a"))

        assertNull(graph.getEntity("a"))
        assertEquals(emptyList(), graph.relationshipsFrom("a"))
        assertEquals(emptyList(), graph.relationshipsTo("a"))
        assertEquals(false, graph.removeRelationship("r1"))
        assertEquals(false, graph.removeRelationship("r2"))
    }

    @Test
    fun `removeEntity of an unsaved id returns false`() {
        assertEquals(false, InMemoryKnowledgeGraph().removeEntity("nope"))
    }

    @Test
    fun `removeRelationship removes it and reports whether it existed`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))

        assertTrue(graph.removeRelationship("r1"))
        assertEquals(false, graph.removeRelationship("r1"))
    }

    @Test
    fun `neighbors is direction-agnostic and dedups`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addEntity(entity("c"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))
        graph.addRelationship(Relationship("r2", "c", "a", "relates_to"))

        assertEquals(listOf("b", "c"), graph.neighbors("a").map { it.id })
    }

    @Test
    fun `neighbors filters by relationshipType`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addEntity(entity("c"))
        graph.addRelationship(Relationship("r1", "a", "b", "depends_on"))
        graph.addRelationship(Relationship("r2", "a", "c", "mentions"))

        assertEquals(listOf("b"), graph.neighbors("a", relationshipType = "depends_on").map { it.id })
    }

    @Test
    fun `traverse walks multiple hops in BFS order and excludes the start id`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addEntity(entity("c"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))
        graph.addRelationship(Relationship("r2", "b", "c", "relates_to"))

        val result = graph.traverse("a", maxDepth = 2)

        assertEquals(listOf("b", "c"), result.map { it.id })
    }

    @Test
    fun `traverse respects the maxDepth limit`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addEntity(entity("c"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))
        graph.addRelationship(Relationship("r2", "b", "c", "relates_to"))

        assertEquals(listOf("b"), graph.traverse("a", maxDepth = 1).map { it.id })
    }

    @Test
    fun `traverse is cycle-safe and never revisits an entity`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))
        graph.addRelationship(Relationship("r2", "b", "a", "relates_to"))

        val result = graph.traverse("a", maxDepth = 5)

        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun `traverse filters by relationshipType at every hop`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))
        graph.addEntity(entity("b"))
        graph.addEntity(entity("c"))
        graph.addRelationship(Relationship("r1", "a", "b", "depends_on"))
        graph.addRelationship(Relationship("r2", "b", "c", "mentions"))

        assertEquals(listOf("b"), graph.traverse("a", maxDepth = 5, relationshipType = "depends_on").map { it.id })
    }

    @Test
    fun `traverse of an entity with no neighbors returns an empty list`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a"))

        assertEquals(emptyList(), graph.traverse("a", maxDepth = 3))
    }
}
