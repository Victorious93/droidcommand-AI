package ai.droidcommand.websearch

import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import ai.droidcommand.remote.RemoteResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Wire shape for Brave Search API's `GET /res/v1/web/search` response, kept
 * separate from [WebSearchResult] for the same reason `core-llm-openai`
 * keeps `OpenAiChatResponse` separate from `LlmResponse` — so the
 * provider-independent contract never depends on one vendor's JSON shape.
 * From Brave's public API documentation; not verified against the live
 * API in this repository (tests use a local mock server only, per the
 * Phase 4 acceptance rule).
 */
@Serializable
private data class BraveSearchResponse(val web: BraveWebResults? = null)

@Serializable
private data class BraveWebResults(val results: List<BraveResult> = emptyList())

@Serializable
private data class BraveResult(
    val title: String = "",
    val url: String = "",
    val description: String = "",
)

/**
 * The primary [WebSearchClient] named by the Consumer Roadmap's Phase 4
 * ("Brave Search primary, SerpAPI fallback"). Auth is Brave's own
 * `X-Subscription-Token` header, not the bearer scheme
 * [RemoteClient]'s built-in `authToken` hook sends — so that hook is left
 * unused here and the token is attached as an ordinary request header
 * instead, read fresh on every call via [apiKey], matching the
 * read-fresh-never-cache convention `LlmConfig.authToken` already uses.
 */
class BraveWebSearchClient(
    private val apiKey: () -> String?,
    transport: HttpTransport,
    endpoint: String = DEFAULT_BASE_URL,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : WebSearchClient {
    private val remoteClient = RemoteClient(RemoteEndpoint(endpoint, requireHttps = requireHttps), transport)
    private val json = Json { ignoreUnknownKeys = true }

    override fun search(query: String, count: Int): WebSearchOutcome {
        val key = apiKey() ?: return WebSearchOutcome.Failure("No Brave Search API key configured")
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

        val result = remoteClient.send(
            path = "res/v1/web/search?q=$encodedQuery&count=$count",
            headers = mapOf("Accept" to "application/json", "X-Subscription-Token" to key),
            retryPolicy = retryPolicy,
        )

        return when (result) {
            is RemoteResult.Success -> parse(result.body)
            is RemoteResult.Failure -> WebSearchOutcome.Failure(result.reason, result.statusCode)
        }
    }

    private fun parse(body: String): WebSearchOutcome = try {
        val response = json.decodeFromString<BraveSearchResponse>(body)
        WebSearchOutcome.Success(
            (response.web?.results ?: emptyList()).map {
                WebSearchResult(title = it.title, url = it.url, snippet = it.description)
            },
        )
    } catch (e: SerializationException) {
        WebSearchOutcome.Failure("Malformed Brave Search response: ${e.message}")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.search.brave.com"
    }
}
