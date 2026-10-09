package ai.droidcommand.websearch

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedWebSearchClient(private val outcome: WebSearchOutcome) : WebSearchClient {
    var lastQuery: String? = null
        private set
    var lastCount: Int? = null
        private set

    override fun search(query: String, count: Int): WebSearchOutcome {
        lastQuery = query
        lastCount = count
        return outcome
    }
}

class WebSearchToolTest {
    @Test
    fun `spec declares SecurityLevel SENSITIVE and PermissionCategory NETWORK, always requiring confirmation`() {
        val spec = WebSearchTool(NullWebSearchClient()).spec
        assertEquals(false, spec.requiresRoot)
        assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
        assertEquals(PermissionCategory.NETWORK, spec.permissionCategory)
        assertTrue(spec.requiresConfirmation)
    }

    @Test
    fun `fails without searching when query is missing`() {
        val client = ScriptedWebSearchClient(WebSearchOutcome.Success(emptyList()))
        val result = WebSearchTool(client).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, client.lastQuery)
    }

    @Test
    fun `fails without searching when query is blank`() {
        val client = ScriptedWebSearchClient(WebSearchOutcome.Success(emptyList()))
        val result = WebSearchTool(client).execute(mapOf("query" to "   "))
        assertIs<ToolResult.Failure>(result)
        assertEquals(null, client.lastQuery)
    }

    @Test
    fun `passes query and default count to the client and formats results`() {
        val client = ScriptedWebSearchClient(
            WebSearchOutcome.Success(
                listOf(WebSearchResult(title = "Kotlin", url = "https://kotlinlang.org", snippet = "A language")),
            ),
        )
        val result = WebSearchTool(client).execute(mapOf("query" to "kotlin"))
        assertIs<ToolResult.Success>(result)
        assertEquals("kotlin", client.lastQuery)
        assertEquals(5, client.lastCount)
        assertTrue(result.output.contains("Kotlin"))
        assertTrue(result.output.contains("https://kotlinlang.org"))
    }

    @Test
    fun `honors an explicit count input`() {
        val client = ScriptedWebSearchClient(WebSearchOutcome.Success(emptyList()))
        WebSearchTool(client).execute(mapOf("query" to "kotlin", "count" to "10"))
        assertEquals(10, client.lastCount)
    }

    @Test
    fun `an invalid count falls back to the default`() {
        val client = ScriptedWebSearchClient(WebSearchOutcome.Success(emptyList()))
        WebSearchTool(client).execute(mapOf("query" to "kotlin", "count" to "-3"))
        assertEquals(5, client.lastCount)
    }

    @Test
    fun `an empty result list is a Success with an explicit no-results message, not an empty string`() {
        val client = ScriptedWebSearchClient(WebSearchOutcome.Success(emptyList()))
        val result = WebSearchTool(client).execute(mapOf("query" to "no hits here"))
        val success = assertIs<ToolResult.Success>(result)
        assertTrue(success.output.isNotBlank())
        assertTrue(success.output.contains("no hits here"))
    }

    @Test
    fun `a client Failure becomes a ToolResult Failure with the same reason`() {
        val client = ScriptedWebSearchClient(WebSearchOutcome.Failure("No Brave Search API key configured"))
        val result = WebSearchTool(client).execute(mapOf("query" to "kotlin"))
        val failure = assertIs<ToolResult.Failure>(result)
        assertEquals("No Brave Search API key configured", failure.reason)
    }

    @Test
    fun `NullWebSearchClient fails cleanly without any real backend`() {
        val result = WebSearchTool(NullWebSearchClient()).execute(mapOf("query" to "kotlin"))
        val failure = assertIs<ToolResult.Failure>(result)
        assertTrue(failure.reason.contains("NullWebSearchClient"))
    }
}
