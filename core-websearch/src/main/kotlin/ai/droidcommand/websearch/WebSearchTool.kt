package ai.droidcommand.websearch

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec

/**
 * Exposes [WebSearchClient] to the agent as a `Tool`, gated by
 * core-security's real `SecureToolExecutor`/`SecurityPolicyEnforcer`
 * exactly like `core-tools-metasploit.MetasploitTool`/
 * `core-tools-setoolkit.SetTool` — sending a query to an external search
 * API is `SENSITIVE`/`PermissionCategory.NETWORK`, always requiring
 * explicit confirmation (never auto-approved, per
 * [ToolSpec.requiresConfirmation]'s own default for a non-`NORMAL`
 * [SecurityLevel]), the same treatment every other network-reaching tool
 * in this repo gets.
 *
 * Input: `query` (required, the search text — never auto-approved, so a
 * caller/planner always surfaces what text is about to leave the device),
 * `count` (optional, defaults to 5, the max number of results requested).
 * A [WebSearchOutcome.Success] with zero hits is reported as
 * [ToolResult.Success] with an explicit "no results" message rather than
 * an empty string, so the planner's conversation context never mistakes
 * silence for a tool failure.
 */
class WebSearchTool(private val client: WebSearchClient) : Tool {
    override val spec = ToolSpec(
        name = "web_search",
        description = "Searches the web for a query and returns titles, URLs and snippets",
        securityLevel = SecurityLevel.SENSITIVE,
        permissionCategory = PermissionCategory.NETWORK,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val query = input["query"]?.takeIf { it.isNotBlank() }
            ?: return ToolResult.Failure("Missing required input 'query'")
        val count = input["count"]?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_COUNT

        return when (val outcome = client.search(query, count)) {
            is WebSearchOutcome.Success -> if (outcome.results.isEmpty()) {
                ToolResult.Success("No results found for '$query'")
            } else {
                ToolResult.Success(
                    outcome.results.joinToString("\n\n") { "${it.title}\n${it.url}\n${it.snippet}" },
                )
            }
            is WebSearchOutcome.Failure -> ToolResult.Failure(outcome.reason)
        }
    }

    companion object {
        private const val DEFAULT_COUNT = 5
    }
}
