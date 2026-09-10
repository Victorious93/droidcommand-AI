package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnowledgeContextTest {
    @Test
    fun `formatting an empty list returns null`() {
        assertNull(formatKnowledgeContext(emptyList()))
    }

    @Test
    fun `formatting entries produces one line per entry with sorted tags rendered inline`() {
        val entries = listOf(
            KnowledgeEntry("a", "The user prefers dark mode", "manual", tags = setOf("ui", "preferences")),
            KnowledgeEntry("b", "The user is on Kotlin 2.4", "manual"),
        )

        val text = formatKnowledgeContext(entries)

        assertEquals(
            "Relevant knowledge:\n" +
                "- The user prefers dark mode [preferences, ui]\n" +
                "- The user is on Kotlin 2.4",
            text,
        )
    }

    @Test
    fun `a custom heading is used when given`() {
        val text = formatKnowledgeContext(listOf(KnowledgeEntry("a", "fact", "manual")), heading = "Known facts:")

        assertEquals("Known facts:\n- fact", text)
    }
}
