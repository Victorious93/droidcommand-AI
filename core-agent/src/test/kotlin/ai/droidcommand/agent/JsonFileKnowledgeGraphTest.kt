package ai.droidcommand.agent

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonFileKnowledgeGraphTest {
    private fun newGraph() = JsonFileKnowledgeGraph(Files.createTempDirectory("droidcommand-knowledge-graph-test"))

    private fun fixedEntity(id: String, type: EntityType = EntityType.NOTE, label: String = id): Entity {
        val at = Instant.parse("2026-01-01T00:00:00Z")
        return Entity(id, type, label, createdAt = at, updatedAt = at)
    }

    @Test
    fun `addEntity then getEntity returns an equal entity`() {
        val graph = newGraph()
        val e = fixedEntity("device-1", EntityType.DEVICE, "Pixel 9")

        graph.addEntity(e)

        assertEquals(e, graph.getEntity("device-1"))
    }

    @Test
    fun `an entity survives being loaded from a fresh instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-knowledge-graph-test")
        val e = fixedEntity("device-1")
        JsonFileKnowledgeGraph(dir).addEntity(e)

        assertEquals(e, JsonFileKnowledgeGraph(dir).getEntity("device-1"))
    }

    @Test
    fun `a relationship survives being loaded from a fresh instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-knowledge-graph-test")
        val graph = JsonFileKnowledgeGraph(dir)
        graph.addEntity(fixedEntity("a"))
        graph.addEntity(fixedEntity("b"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to", createdAt = Instant.parse("2026-01-01T00:00:00Z")))

        val reopened = JsonFileKnowledgeGraph(dir)

        assertEquals(listOf("r1"), reopened.relationshipsFrom("a").map { it.id })
    }

    @Test
    fun `getEntity of an unsaved id returns null`() {
        assertNull(newGraph().getEntity("nope"))
    }

    @Test
    fun `addRelationship throws UnknownEntityException for a missing endpoint`() {
        val graph = newGraph()
        graph.addEntity(fixedEntity("a"))

        assertFailsWith<UnknownEntityException> { graph.addRelationship(Relationship("r1", "a", "b", "relates_to")) }
    }

    @Test
    fun `removeEntity cascades to relationships, re-reading from disk not a cache`() {
        val dir = Files.createTempDirectory("droidcommand-knowledge-graph-test")
        val graph = JsonFileKnowledgeGraph(dir)
        graph.addEntity(fixedEntity("a"))
        graph.addEntity(fixedEntity("b"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))

        val reopened = JsonFileKnowledgeGraph(dir)
        assertTrue(reopened.removeEntity("a"))

        assertNull(graph.getEntity("a"))
        assertEquals(emptyList(), graph.relationshipsFrom("a"))
        assertEquals(false, graph.removeRelationship("r1"))
    }

    @Test
    fun `an entity id containing a path separator is rejected rather than escaping the directory`() {
        val graph = newGraph()
        assertFailsWith<InvalidEntityId> { graph.addEntity(fixedEntity("../escape")) }
        assertFailsWith<InvalidEntityId> { graph.getEntity("../escape") }
        assertFailsWith<InvalidEntityId> { graph.removeEntity("../escape") }
    }

    @Test
    fun `an absolute-path-shaped entity id is rejected`() {
        val graph = newGraph()
        assertFailsWith<InvalidEntityId> { graph.addEntity(fixedEntity("/etc/passwd")) }
    }

    @Test
    fun `a relationship id containing a path separator is rejected rather than escaping the directory`() {
        val graph = newGraph()
        graph.addEntity(fixedEntity("a"))
        graph.addEntity(fixedEntity("b"))

        assertFailsWith<InvalidRelationshipId> { graph.addRelationship(Relationship("../escape", "a", "b", "relates_to")) }
    }

    @Test
    fun `traverse walks relationships re-read fresh from disk`() {
        val dir = Files.createTempDirectory("droidcommand-knowledge-graph-test")
        val graph = JsonFileKnowledgeGraph(dir)
        graph.addEntity(fixedEntity("a"))
        graph.addEntity(fixedEntity("b"))
        graph.addEntity(fixedEntity("c"))
        graph.addRelationship(Relationship("r1", "a", "b", "relates_to"))
        graph.addRelationship(Relationship("r2", "b", "c", "relates_to"))

        val reopened = JsonFileKnowledgeGraph(dir)

        assertEquals(listOf("b", "c"), reopened.traverse("a", maxDepth = 2).map { it.id })
    }

    @Test
    fun `entitiesByType and searchEntities genuinely re-read from a freshly reopened graph`() {
        val dir = Files.createTempDirectory("droidcommand-knowledge-graph-test")
        JsonFileKnowledgeGraph(dir).addEntity(fixedEntity("a", EntityType.DEVICE, "Pixel 9"))

        val reopened = JsonFileKnowledgeGraph(dir)

        assertEquals(listOf("a"), reopened.entitiesByType(EntityType.DEVICE).map { it.id })
        assertEquals(listOf("a"), reopened.searchEntities("pixel").map { it.id })
    }
}
