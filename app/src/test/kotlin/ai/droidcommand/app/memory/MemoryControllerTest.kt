package ai.droidcommand.app.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.Relationship
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plain JUnit (no Robolectric, no Android types reachable from [MemoryController]) — the app module's
 * first unit tests, run against the same [InMemoryKnowledgeGraph] `core-agent`'s own differential test
 * already trusts as a correctness reference.
 */
class MemoryControllerTest {
    private fun entity(id: String, label: String, updatedAt: Instant) =
        Entity(id, EntityType.CONCEPT, label, updatedAt = updatedAt, createdAt = updatedAt)

    @Test
    fun `all lists every entity, most recently updated first`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a", "Older", Instant.ofEpochSecond(1)))
        graph.addEntity(entity("b", "Newer", Instant.ofEpochSecond(2)))
        val controller = MemoryController(graph)

        assertEquals(listOf("Newer", "Older"), controller.all().map { it.label })
    }

    @Test
    fun `all is empty when nothing has been remembered`() {
        val controller = MemoryController(InMemoryKnowledgeGraph())
        assertTrue(controller.all().isEmpty())
    }

    @Test
    fun `delete removes the entity and cascades to its relationships`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a", "Router", Instant.ofEpochSecond(1)))
        graph.addEntity(entity("b", "Home", Instant.ofEpochSecond(1)))
        graph.addRelationship(Relationship("r1", "a", "b", "located_in"))
        val controller = MemoryController(graph)

        assertTrue(controller.delete("a"))
        assertTrue(controller.all().none { it.id == "a" })
        assertTrue(graph.relationshipsFrom("b").isEmpty(), "the edge should have cascaded away with the entity")
        assertEquals(false, controller.delete("a"), "deleting an id that no longer exists reports false")
    }

    @Test
    fun `clearAll removes every entity and returns the count removed`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a", "One", Instant.ofEpochSecond(1)))
        graph.addEntity(entity("b", "Two", Instant.ofEpochSecond(2)))
        graph.addEntity(entity("c", "Three", Instant.ofEpochSecond(3)))
        val controller = MemoryController(graph)

        assertEquals(3, controller.clearAll())
        assertTrue(controller.all().isEmpty())
        assertEquals(0, controller.clearAll(), "clearing an already-empty graph removes nothing")
    }
}
