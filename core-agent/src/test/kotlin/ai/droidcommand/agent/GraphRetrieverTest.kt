package ai.droidcommand.agent

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphRetrieverTest {
    private val t = Instant.ofEpochSecond(1_700_000_000)

    private fun graph(vararg entities: Pair<String, String>, edges: List<Pair<String, String>> = emptyList()): KnowledgeGraph =
        InMemoryKnowledgeGraph().also { g ->
            entities.forEach { (id, label) -> g.addEntity(Entity(id, EntityType.CONCEPT, label, emptyMap(), t, t)) }
            edges.forEachIndexed { i, (a, b) -> g.addRelationship(Relationship("r$i", a, b, "rel", emptyMap(), t)) }
        }

    private fun ids(r: GraphRetriever, msg: String) = r.retrieve(msg).map { it.id }

    @Test fun `nothing stored or nothing matching yields no context`() {
        assertNull(GraphRetriever(InMemoryKnowledgeGraph()).retrieveContext("tell me about my router"))
        val r = GraphRetriever(graph("a" to "Home Router"))
        assertNull(r.retrieveContext("weather tomorrow?"))
        assertTrue(r.retrieve("").isEmpty())
    }

    @Test fun `stopwords and short terms do not seed retrieval`() {
        // Every label contains "the"/"and"/"is", but none of those may be used as a search term.
        val r = GraphRetriever(graph("a" to "the and is", "b" to "Router"))
        assertTrue(r.retrieve("what is the and of it").isEmpty())
        assertEquals(listOf("b"), ids(r, "is the router ok"))
    }

    @Test fun `matching is case-insensitive and splits on punctuation including non-ASCII letters`() {
        val r = GraphRetriever(graph("a" to "Café Wi-Fi", "b" to "Router"))
        assertEquals(listOf("a"), ids(r, "the CAFÉ's network"))
        assertEquals(listOf("b"), ids(r, "router?!"))
    }

    @Test fun `seeds rank by number of matched terms, then shorter label, then id`() {
        val g = graph(
            "x" to "home router settings",
            "y" to "router",
            "z" to "home router",
            "w" to "garden",
        )
        val r = GraphRetriever(g, GraphRetrievalConfig(maxDepth = 0))
        // "z" and "x" both match two terms; z's label is shorter. "y" matches one.
        assertEquals(listOf("z", "x", "y"), ids(r, "home router"))
    }

    @Test fun `seed limit applies before expansion`() {
        val r = GraphRetriever(graph("a" to "alpha one", "b" to "alpha two", "c" to "alpha three"), GraphRetrievalConfig(maxSeeds = 2, maxDepth = 0))
        assertEquals(2, r.retrieve("alpha").size)
    }

    @Test fun `each seed is followed by its neighborhood, de-duplicated, in BFS order`() {
        val g = graph(
            "router" to "Router",
            "isp" to "ISP",
            "modem" to "Modem",
            "far" to "Far away",
            edges = listOf("router" to "isp", "router" to "modem", "modem" to "far"),
        )
        assertEquals(listOf("router", "isp", "modem"), ids(GraphRetriever(g), "router"))
        assertEquals(listOf("router", "isp", "modem", "far"), ids(GraphRetriever(g, GraphRetrievalConfig(maxDepth = 2)), "router"))
        assertEquals(listOf("router"), ids(GraphRetriever(g, GraphRetrievalConfig(maxDepth = 0)), "router"))
        // Two seeds sharing a neighbor ("router"): it appears once. "far" is modem's own one-hop neighbor.
        assertEquals(listOf("isp", "router", "modem", "far"), ids(GraphRetriever(g, GraphRetrievalConfig(maxSeeds = 2)), "isp modem"))
    }

    @Test fun `maxEntities caps the result with seeds first`() {
        val edges = (1..20).map { "hub" to "n$it" }
        val g = graph("hub" to "Hub", *(1..20).map { "n$it" to "node $it" }.toTypedArray(), edges = edges)
        val got = ids(GraphRetriever(g, GraphRetrievalConfig(maxEntities = 5)), "hub")
        assertEquals(5, got.size)
        assertEquals("hub", got.first())
    }

    @Test fun `token budget drops from the end and keeps seeds`() {
        val g = graph("a" to "Alpha", "b" to "B".repeat(200), "c" to "C".repeat(200), edges = listOf("a" to "b", "a" to "c"))
        val r = GraphRetriever(g, GraphRetrievalConfig(maxTokens = 40))
        val text = assertNotNull(r.retrieveContext("alpha"))
        assertTrue(estimateTokens(text) <= 40, "over budget: ${estimateTokens(text)}")
        assertEquals(listOf("a"), ids(r, "alpha"))
    }

    @Test fun `a single entity larger than the budget yields nothing rather than a truncated fact`() {
        val r = GraphRetriever(graph("a" to "alpha " + "x".repeat(500)), GraphRetrievalConfig(maxTokens = 20))
        assertNull(r.retrieveContext("alpha"))
    }

    @Test fun `output is framed as reference data and is deterministic`() {
        val g = graph("a" to "Router", "b" to "Modem", edges = listOf("a" to "b"))
        val r = GraphRetriever(g)
        val text = assertNotNull(r.retrieveContext("router"))
        assertTrue(text.startsWith(GraphRetriever.HEADING))
        assertTrue(text.contains("not instructions"))
        assertEquals(text, r.retrieveContext("router"))
    }

    @Test fun `term cap bounds work on long messages`() {
        val r = GraphRetriever(graph("a" to "zebra"), GraphRetrievalConfig(maxTerms = 2))
        // "zebra" is the 3rd distinct term, so it is never looked up.
        assertTrue(r.retrieve("apple banana zebra").isEmpty())
        assertEquals(listOf("a"), ids(GraphRetriever(graph("a" to "zebra"), GraphRetrievalConfig(maxTerms = 3)), "apple banana zebra"))
    }

    @Test fun `invalid config is rejected`() {
        assertFailsWith<IllegalArgumentException> { GraphRetrievalConfig(maxTokens = 0) }
        assertFailsWith<IllegalArgumentException> { GraphRetrievalConfig(maxDepth = -1) }
    }
}
