package ai.droidcommand.cli

import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.websearch.BraveWebSearchClient
import ai.droidcommand.websearch.FallbackWebSearchClient
import ai.droidcommand.websearch.NullWebSearchClient
import ai.droidcommand.websearch.SerpApiWebSearchClient
import ai.droidcommand.websearch.WebSearchClient

internal const val BRAVE_API_KEY_ENV = "DROIDCOMMAND_CLI_BRAVE_API_KEY"
internal const val SERPAPI_API_KEY_ENV = "DROIDCOMMAND_CLI_SERPAPI_API_KEY"

/**
 * The client behind `web_search`: off (falling back to
 * [NullWebSearchClient], which fails cleanly) unless at least one of
 * [BRAVE_API_KEY_ENV]/[SERPAPI_API_KEY_ENV] is set — the same
 * opt-in-only pattern [metasploitExecutorFor]/[setExecutorFor] already
 * establish, so this CLI never reaches out to a real search API by
 * surprise. Unlike those two, there is no shell-executable allowlist to
 * also configure: a web search is an HTTP call through [transport], not a
 * spawned process.
 *
 * With both keys set, wraps them in [FallbackWebSearchClient] per the
 * Consumer Roadmap's own "Brave Search primary, SerpAPI fallback"
 * wording; with only one set, that single client is used directly rather
 * than wrapping it in a fallback with no second provider to fall back to.
 * Each key is read fresh on every call (the `apiKey: () -> String?`
 * lambda both clients take), matching `LlmConfig.authToken`'s
 * read-fresh-never-cache convention — not captured once at startup.
 */
internal fun webSearchClientFor(
    transport: HttpTransport,
    env: (String) -> String? = System::getenv,
): WebSearchClient {
    val braveKey = { env(BRAVE_API_KEY_ENV)?.takeIf { it.isNotBlank() } }
    val serpApiKey = { env(SERPAPI_API_KEY_ENV)?.takeIf { it.isNotBlank() } }
    val hasBrave = braveKey() != null
    val hasSerpApi = serpApiKey() != null

    return when {
        hasBrave && hasSerpApi -> FallbackWebSearchClient(
            BraveWebSearchClient(braveKey, transport),
            SerpApiWebSearchClient(serpApiKey, transport),
        )
        hasBrave -> BraveWebSearchClient(braveKey, transport)
        hasSerpApi -> SerpApiWebSearchClient(serpApiKey, transport)
        else -> NullWebSearchClient()
    }
}
