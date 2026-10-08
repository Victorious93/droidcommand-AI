package ai.droidcommand.app.ui.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.Relationship
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryControllerTest {
    private val t = Instant.ofEpochSecond(1_700_000_000)

    private fun graphWith(vararg e: Entity) = InMemoryKnowledgeGraph().also { g -> e.forEach(g::addEntity) }

    private fun ent(id: String, label: String, type: EntityType = EntityType.CONCEPT) = Entity(id, type, label, emptyMap(), t, t)

    @Test fun `all lists every entity grouped by type then label case-insensitively then id`() {
        val c = MemoryController(
            graphWith(
                ent("3", "zebra", EntityType.CONCEPT),
                ent("1", "Apple", EntityType.DEVICE),
                ent("2", "apple", EntityType.CONCEPT),
                ent("0", "Banana", EntityType.CONCEPT),
            ),
        )
        assertEquals(listOf("2", "0", "3", "1"), c.all().map { it.id }) // CONCEPT (apple,banana,zebra) before DEVICE (Apple)
    }

    @Test fun `empty graph lists nothing`() {
        assertTrue(MemoryController(InMemoryKnowledgeGraph()).all().isEmpty())
    }

    @Test fun `delete removes the entity and cascades its relationships`() {
        val g = graphWith(ent("a", "A"), ent("b", "B"))
        g.addRelationship(Relationship("r", "a", "b", "rel", emptyMap(), t))
        val c = MemoryController(g)
        assertTrue(c.delete("a"))
        assertFalse(c.delete("a"))
        assertEquals(listOf("b"), c.all().map { it.id })
        assertTrue(g.relationshipsTo("b").isEmpty(), "relationship should have cascaded")
    }

    @Test fun `clearAll removes everything and reports the count`() {
        val g = graphWith(ent("a", "A"), ent("b", "B"), ent("c", "C"))
        g.addRelationship(Relationship("r", "a", "b", "rel", emptyMap(), t))
        val c = MemoryController(g)
        assertEquals(3, c.clearAll())
        assertTrue(c.all().isEmpty())
        assertEquals(0, c.clearAll())
    }
}
