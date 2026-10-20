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

    @Test
    fun `rename changes the label, bumps updatedAt and keeps type, properties and relationships`() {
        val graph = InMemoryKnowledgeGraph()
        val original = Entity("a", EntityType.DEVICE, "Router", mapOf("k" to "v"), Instant.ofEpochSecond(1))
        graph.addEntity(original)
        graph.addEntity(entity("b", "Switch", Instant.ofEpochSecond(1)))
        graph.addRelationship(Relationship("r", "a", "b", "connects_to"))

        val updated = MemoryController(graph).rename("a", "  Home router  ")

        assertEquals("Home router", updated?.label)
        val stored = graph.getEntity("a")!!
        assertEquals("Home router", stored.label)
        assertEquals(EntityType.DEVICE, stored.type)
        assertEquals(mapOf("k" to "v"), stored.properties)
        assertTrue(stored.updatedAt.isAfter(original.updatedAt))
        assertEquals(listOf("b"), graph.relationshipsFrom("a").map { it.toId })
    }

    @Test
    fun `rename refuses a blank label or an unknown id and changes nothing`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a", "Router", Instant.ofEpochSecond(1)))
        val controller = MemoryController(graph)

        assertEquals(null, controller.rename("a", "   "))
        assertEquals(null, controller.rename("missing", "X"))
        assertEquals("Router", graph.getEntity("a")?.label)
    }

    @Test
    fun `update changes label, type and properties together and keeps relationships`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(Entity("a", EntityType.CONCEPT, "Router", mapOf("old" to "1"), Instant.ofEpochSecond(1)))
        graph.addEntity(entity("b", "Switch", Instant.ofEpochSecond(1)))
        graph.addRelationship(Relationship("r", "a", "b", "connects_to"))

        val updated = MemoryController(graph).update(
            "a",
            " Home router ",
            EntityType.DEVICE,
            mapOf(" ip " to " 10.0.0.1 ", "  " to "dropped"),
        )

        assertEquals("Home router", updated?.label)
        assertEquals(EntityType.DEVICE, updated?.type)
        assertEquals(mapOf("ip" to "10.0.0.1"), updated?.properties)
        assertEquals(listOf("b"), graph.relationshipsFrom("a").map { it.toId })
    }

    @Test
    fun `update with nothing changed returns the stored entity untouched`() {
        val graph = InMemoryKnowledgeGraph()
        val original = Entity("a", EntityType.DEVICE, "Router", mapOf("k" to "v"), Instant.ofEpochSecond(1))
        graph.addEntity(original)

        assertEquals(original, MemoryController(graph).update("a", "Router", EntityType.DEVICE, mapOf("k" to "v")))
        assertEquals(Instant.ofEpochSecond(1), graph.getEntity("a")?.updatedAt)
    }

    @Test
    fun `update refuses a blank label or an unknown id`() {
        val graph = InMemoryKnowledgeGraph()
        graph.addEntity(entity("a", "Router", Instant.ofEpochSecond(1)))
        val controller = MemoryController(graph)

        assertEquals(null, controller.update("a", " ", EntityType.DEVICE, emptyMap()))
        assertEquals(null, controller.update("missing", "X", EntityType.DEVICE, emptyMap()))
        assertEquals(EntityType.CONCEPT, graph.getEntity("a")?.type)
    }

    @Test
    fun `properties text round-trips and ignores malformed lines`() {
        val parsed = MemoryController.parseProperties("ip = 10.0.0.1\nnoequals\n=novalue-key\nurl=http://x/?a=b\n\nip=10.0.0.2")
        assertEquals(mapOf("ip" to "10.0.0.2", "url" to "http://x/?a=b"), parsed)
        assertEquals(parsed, MemoryController.parseProperties(MemoryController.formatProperties(parsed)))
        assertEquals("", MemoryController.formatProperties(emptyMap()))
    }
}
