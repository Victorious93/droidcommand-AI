package ai.droidcommand.knowledge.android

import ai.droidcommand.agent.Entity
import ai.droidcommand.agent.EntityType
import ai.droidcommand.agent.InMemoryKnowledgeGraph
import ai.droidcommand.agent.KnowledgeGraph
import ai.droidcommand.agent.Relationship
import ai.droidcommand.agent.UnknownEntityException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real Room over Robolectric's SQLite, on the JVM. NOT an on-device test: no migrations, no real
 * Android SQLite quirks (including the 999-variable limit older devices have), no
 * `connectedAndroidTest`. The differential test shows behavioral equivalence with
 * [InMemoryKnowledgeGraph] over the operations it generates, not over every possible input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomKnowledgeGraphTest {
    private lateinit var db: KnowledgeDatabase
    private lateinit var graph: RoomKnowledgeGraph

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnowledgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        graph = RoomKnowledgeGraph(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun ent(id: String, label: String = id, type: EntityType = EntityType.CONCEPT, props: Map<String, String> = emptyMap(), t: Instant = T0) =
        Entity(id, type, label, props, t, t)

    private fun rel(id: String, from: String, to: String, type: String = "r", props: Map<String, String> = emptyMap()) =
        Relationship(id, from, to, type, props, T0)

    @Test
    fun `entity round trips including properties and nanosecond timestamps`() {
        val e = ent("a", "Alpha ünï", EntityType.DEVICE, mapOf("k" to "v", "x" to ""), Instant.ofEpochSecond(1_700_000_000, 123_456_789))
        graph.addEntity(e)
        assertEquals(e, graph.getEntity("a"))
        assertNull(graph.getEntity("nope"))
    }

    @Test
    fun `updating an entity keeps its relationships and replaces its properties`() {
        graph.addEntity(ent("a", props = mapOf("old" to "1")))
        graph.addEntity(ent("b"))
        graph.addRelationship(rel("r1", "a", "b"))
        graph.addEntity(ent("a", label = "renamed", props = mapOf("new" to "2")))
        assertEquals(listOf("r1"), graph.relationshipsFrom("a").map { it.id }, "an update must not cascade-delete edges")
        assertEquals(mapOf("new" to "2"), graph.getEntity("a")?.properties)
        assertEquals("renamed", graph.getEntity("a")?.label)
    }

    @Test
    fun `relationship with an unknown endpoint is rejected naming the missing id`() {
        graph.addEntity(ent("a"))
        assertEquals("b", assertFailsWith<UnknownEntityException> { graph.addRelationship(rel("r", "a", "b")) }.id)
        assertEquals("z", assertFailsWith<UnknownEntityException> { graph.addRelationship(rel("r", "z", "a")) }.id)
        assertTrue(graph.relationshipsFrom("a").isEmpty())
    }

    @Test
    fun `removing an entity removes edges in both directions and its properties`() {
        listOf("a", "b", "c").forEach { graph.addEntity(ent(it, props = mapOf("k" to it))) }
        graph.addRelationship(rel("r1", "a", "b", props = mapOf("w" to "1")))
        graph.addRelationship(rel("r2", "c", "a"))
        graph.addRelationship(rel("r3", "b", "c"))
        assertTrue(graph.removeEntity("a"))
        assertFalse(graph.removeEntity("a"))
        assertTrue(graph.relationshipsTo("b").isEmpty())
        assertTrue(graph.relationshipsFrom("c").isEmpty())
        assertEquals(listOf("r3"), graph.relationshipsFrom("b").map { it.id })
        assertEquals(
            0,
            db.query("SELECT COUNT(*) FROM kg_entity_properties WHERE entity_id = 'a'", null).use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
    }

    @Test
    fun `saving a relationship under an existing id replaces it and its properties`() {
        listOf("a", "b", "c").forEach { graph.addEntity(ent(it)) }
        graph.addRelationship(rel("r", "a", "b", "old", mapOf("p" to "1")))
        graph.addRelationship(rel("r", "a", "c", "new", mapOf("q" to "2")))
        assertEquals(listOf(rel("r", "a", "c", "new", mapOf("q" to "2"))), graph.relationshipsFrom("a"))
        assertTrue(graph.relationshipsTo("b").isEmpty())
    }

    @Test
    fun `traverse is breadth-first, terminates on cycles, excludes the start, and validates depth`() {
        listOf("a", "b", "c", "d").forEach { graph.addEntity(ent(it)) }
        graph.addRelationship(rel("1", "a", "b"))
        graph.addRelationship(rel("2", "b", "c"))
        graph.addRelationship(rel("3", "c", "a")) // cycle
        graph.addRelationship(rel("4", "c", "d"))
        assertEquals(listOf("b", "c"), graph.traverse("a", 1).map { it.id })
        assertEquals(listOf("b", "c", "d"), graph.traverse("a", 5).map { it.id })
        assertTrue(graph.traverse("a", 0).isEmpty())
        assertFailsWith<IllegalArgumentException> { graph.traverse("a", -1) }
    }

    @Test
    fun `a node with more neighbors than one SQL batch still returns all of them`() {
        graph.addEntity(ent("hub"))
        repeat(1_200) { i ->
            graph.addEntity(ent("n%04d".format(i)))
            graph.addRelationship(rel("e%04d".format(i), "hub", "n%04d".format(i)))
        }
        assertEquals(1_200, graph.neighbors("hub").size)
        assertEquals(1_200, graph.traverse("hub", 1).size)
    }

    @Test
    fun `search is a case-insensitive literal substring match`() {
        graph.addEntity(ent("a", label = "Home Router"))
        graph.addEntity(ent("b", label = "ÉCOLE"))
        graph.addEntity(ent("c", label = "100% sure_thing"))
        assertEquals(listOf("a"), graph.searchEntities("router").map { it.id })
        assertEquals(listOf("b"), graph.searchEntities("école").map { it.id }) // non-ASCII folding, which SQLite lower() would miss
        assertEquals(listOf("c"), graph.searchEntities("%").map { it.id }) // not treated as a wildcard
        assertEquals(listOf("c"), graph.searchEntities("_t").map { it.id })
        assertEquals(3, graph.searchEntities("").size)
    }

    @Test
    fun `an entity type from a newer build reads back as CONCEPT`() {
        db.dao().upsertEntity(EntityRow("f", "HOLOGRAM", "x", T0.toString(), T0.toString()))
        assertEquals(EntityType.CONCEPT, graph.getEntity("f")?.type)
    }

    @Test
    fun `data in a file-backed database survives close and reopen`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "kg-reopen.db"
        ctx.deleteDatabase(name)
        val first = Room.databaseBuilder(ctx, KnowledgeDatabase::class.java, name).allowMainThreadQueries().build()
        RoomKnowledgeGraph(first).also {
            it.addEntity(ent("a", props = mapOf("k" to "v")))
            it.addEntity(ent("b"))
            it.addRelationship(rel("r", "a", "b"))
        }
        first.close()
        val second = Room.databaseBuilder(ctx, KnowledgeDatabase::class.java, name).allowMainThreadQueries().build()
        try {
            val reopened = RoomKnowledgeGraph(second)
            assertNotNull(reopened.getEntity("a"))
            assertEquals(listOf("b"), reopened.neighbors("a").map { it.id })
            assertEquals(mapOf("k" to "v"), reopened.getEntity("a")?.properties)
        } finally {
            second.close()
            ctx.deleteDatabase(name)
        }
    }

    // ---- differential test against the reference implementation ----

    private class Snapshot(g: KnowledgeGraph, ids: List<String>) {
        val values: List<Any?> = buildList {
            for (id in ids) {
                add(g.getEntity(id))
                add(g.relationshipsFrom(id))
                add(g.relationshipsTo(id))
                for (t in listOf(null, "r0", "r1")) {
                    add(g.neighbors(id, t))
                    for (depth in 0..3) add(g.traverse(id, depth, t))
                }
            }
            EntityType.entries.forEach { add(g.entitiesByType(it)) }
            listOf("", "a", "É", "é", "ß", "x1", "😀").forEach { add(g.searchEntities(it)) }
        }
    }

    @Test
    fun `behaves identically to the in-memory reference over seeded random operations`() {
        val labels = listOf("alpha", "Beta", "É clair", "école", "ß", "x1", "x10", "😀 smile", "")
        for (seed in 1..25) {
            val rnd = Random(seed)
            val ref = InMemoryKnowledgeGraph()
            val db2 = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnowledgeDatabase::class.java)
                .allowMainThreadQueries().build()
            val sut = RoomKnowledgeGraph(db2)
            val ids = (0 until 10).map { "e$it" }
            try {
                repeat(150) { step ->
                    val op = rnd.nextInt(10)
                    val id = ids.random(rnd)
                    val other = ids.random(rnd)
                    val relId = "rel${rnd.nextInt(14)}"
                    when {
                        op < 4 -> {
                            val e = Entity(
                                id,
                                EntityType.entries.random(rnd),
                                labels.random(rnd),
                                (0 until rnd.nextInt(3)).associate { "k${rnd.nextInt(3)}" to "v${rnd.nextInt(5)}" },
                                Instant.ofEpochSecond(rnd.nextLong(0, 2_000_000_000), rnd.nextLong(0, 1_000_000_000)),
                                Instant.ofEpochSecond(rnd.nextLong(0, 2_000_000_000), rnd.nextLong(0, 1_000_000_000)),
                            )
                            ref.addEntity(e)
                            sut.addEntity(e)
                        }
                        op < 7 -> {
                            val r = Relationship(
                                relId,
                                id,
                                other,
                                "r${rnd.nextInt(2)}",
                                (0 until rnd.nextInt(2)).associate { "p${rnd.nextInt(2)}" to "w${rnd.nextInt(3)}" },
                                Instant.ofEpochSecond(rnd.nextLong(0, 2_000_000_000), rnd.nextLong(0, 1_000_000_000)),
                            )
                            val expected = runCatching { ref.addRelationship(r) }.exceptionOrNull()
                            val actual = runCatching { sut.addRelationship(r) }.exceptionOrNull()
                            assertEquals(expected?.javaClass, actual?.javaClass, "seed=$seed step=$step addRelationship")
                            if (expected is UnknownEntityException) assertEquals(expected.id, (actual as UnknownEntityException).id)
                        }
                        op < 9 -> assertEquals(ref.removeEntity(id), sut.removeEntity(id), "seed=$seed step=$step removeEntity")
                        else -> assertEquals(ref.removeRelationship(relId), sut.removeRelationship(relId), "seed=$seed step=$step removeRelationship")
                    }
                    if (step % 25 == 24) assertEquals(Snapshot(ref, ids).values, Snapshot(sut, ids).values, "seed=$seed step=$step")
                }
                assertEquals(Snapshot(ref, ids).values, Snapshot(sut, ids).values, "seed=$seed final")
            } finally {
                db2.close()
            }
        }
    }

    private companion object {
        val T0: Instant = Instant.ofEpochSecond(1_700_000_000)
    }
}
