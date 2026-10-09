package ai.droidcommand.agent.memory

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryAdminTest {
    private val graph = InMemoryKnowledgeGraph()
    private val forgotten = mutableListOf<String>()
    private val admin = MemoryAdmin(graph, onForgotten = { forgotten += it })
    private val writer = MemoryWriter(graph)

    private fun store(content: String, scope: String = "project:a", cls: MemoryClass = MemoryClass.PROJECT): String {
        val d = writer.write(MemoryCandidate("t-$content".take(20), content, cls, scope, Origin.USER))
        return (d as MemoryDecision.Stored).entity.id
    }

    @Test
    fun `forget removes the memory, its edges and fires the index hook`() {
        val a = store("alpha note")
        val b = store("beta note")
        graph.addRelationship(ai.droidcommand.agent.Relationship("r", a, b, MemoryRelations.MEMORY_RELATES_TO))
        assertTrue(admin.forget(a))
        assertNull(graph.getEntity(a))
        assertTrue(graph.relationshipsTo(b).isEmpty())
        assertEquals(listOf(a), forgotten)
        assertFalse(admin.forget(a))
    }

    @Test
    fun `forget refuses to delete a non-memory entity`() {
        graph.addEntity(Entity("plain", EntityType.NOTE, "not a memory"))
        assertFalse(admin.forget("plain"))
        assertNotNull(graph.getEntity("plain"))
    }

    @Test
    fun `forgetScope deletes only that scope and leaves other entities alone`() {
        store("one", "project:a"); store("two", "project:a"); val keep = store("three", "project:b")
        graph.addEntity(Entity("plain", EntityType.NOTE, "plain"))
        assertEquals(2, admin.forgetScope("project:a"))
        assertEquals(listOf(keep), admin.list().map { it.id })
        assertNotNull(graph.getEntity("plain"))
    }

    @Test
    fun `edit rejects a smuggled credential and drops the stale summary`() {
        val id = store("clean content")
        assertNotNull(admin.edit(id, content = "token = abcdefghijkl1234"))
        assertEquals("clean content", admin.inspect(id)!!.meta.content)
        assertNull(admin.edit(id, content = "rewritten content"))
        val r = admin.inspect(id)!!
        assertEquals("rewritten content", r.meta.content)
        assertEquals(Verification.USER_ASSERTED, r.meta.verification)
        assertNull(r.meta.summary)
    }

    @Test
    fun `pin toggles and a superseded record cannot be pinned`() {
        val id = store("x note")
        assertTrue(admin.pin(id, true)); assertTrue(admin.inspect(id)!!.meta.pinned)
        val old = writer.write(MemoryCandidate("a", "v1 text", MemoryClass.PROJECT, "project:a", Origin.USER, subject = "s")) as MemoryDecision.Stored
        writer.write(MemoryCandidate("a", "v2 different", MemoryClass.PROJECT, "project:a", Origin.USER, subject = "s"))
        assertFalse(admin.pin(old.entity.id, true))
        assertFalse(admin.pin("missing", true))
    }

    @Test
    fun `export is valid JSON with provenance and honours scope`() {
        store("exported fact", "project:a"); store("other scope", "project:b")
        val root = Json.parseToJsonElement(admin.export(setOf("project:a"))).jsonObject
        val items = root.getValue("memories").jsonArray
        assertEquals(1, items.size)
        assertEquals("USER_ASSERTED", items[0].jsonObject.getValue("verification").toString().trim('"'))
    }
}
