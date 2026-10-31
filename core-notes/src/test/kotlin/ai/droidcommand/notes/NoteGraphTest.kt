package ai.droidcommand.notes

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteGraphTest {
    private fun vault(): NoteVault = NoteVault(Files.createTempDirectory("note-graph"))

    @Test
    fun `wikilinks become directed edges and unknown targets are dropped`() {
        val vault = vault()
        vault.write("ideas.md", "See [[Home Router]] and [[Missing]].")
        vault.write(
            "router.md",
            """
            ---
            title: Home Router
            tags: [network]
            ---
            The AP.
            """.trimIndent(),
        )
        val graph = NoteGraph.from(NoteIndex(vault).refresh())
        assertEquals(setOf("ideas.md", "router.md"), graph.nodes.map { it.path }.toSet())
        assertEquals(listOf(GraphEdge("ideas.md", "router.md")), graph.edges)
        assertEquals(setOf("network"), graph.nodes.single { it.path == "router.md" }.tags)
    }

    @Test
    fun `layout places two notes at different points and hit finds the nearest`() {
        val graph = NoteGraph(
            nodes = listOf(GraphNode("a.md", "A"), GraphNode("b.md", "B")),
            edges = emptyList(),
        )
        val points = graph.layout(200f, 200f)
        assertEquals(2, points.size)
        assertNotEquals(points[0].x to points[0].y, points[1].x to points[1].y)
        assertEquals("a.md", graph.hit(200f, 200f, points[0].x, points[0].y))
        assertNull(graph.hit(200f, 200f, 1000f, 1000f))
        assertTrue(graph.layout(0f, 200f).isEmpty())
    }

    @Test
    fun `a single note sits in the centre`() {
        val graph = NoteGraph(nodes = listOf(GraphNode("only.md", "Only")), edges = emptyList())
        val point = graph.layout(100f, 80f).single()
        assertEquals("only.md", point.path)
        assertEquals(50f, point.x)
        assertEquals(40f, point.y)
    }
}
