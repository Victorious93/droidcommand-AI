package ai.droidcommand.n8n

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class N8nResultWordsTest {
    @Test
    fun `success with an empty body is a plain sentence`() {
        val words = N8nResultWords.describe(200, "", "Lights")
        assertTrue(words.contains("Lights"))
        assertTrue(words.contains("no extra detail"))
    }

    @Test
    fun `json message is preferred over the raw dump`() {
        val words = N8nResultWords.describe(500, """{"message":"workflow crashed"}""", "Lights")
        assertTrue(words.contains("workflow crashed"))
        assertFalse(words.contains("{"))
    }

    @Test
    fun `404 names the workflow and asks if it is on`() {
        val words = N8nResultWords.describe(404, "", "Doorbell")
        assertTrue(words.contains("Doorbell"))
        assertTrue(words.contains("switched on") || words.contains("on"))
    }

    @Test
    fun `a long body is shortened`() {
        val words = N8nResultWords.describe(200, "x".repeat(800), "Lights")
        assertTrue(words.endsWith("…"))
        assertTrue(words.length < 500)
    }

    @Test
    fun `connection copy reports the count`() {
        assertTrue(N8nResultWords.connection(200, 0).contains("0"))
        assertTrue(N8nResultWords.connection(200, 1).contains("1 workflow"))
        assertTrue(N8nResultWords.connection(401, null).contains("API key"))
    }
}
