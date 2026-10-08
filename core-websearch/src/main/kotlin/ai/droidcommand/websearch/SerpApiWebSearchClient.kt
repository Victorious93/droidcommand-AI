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
 * Wire shape for SerpAPI's `GET /search.json` Google-engine response. From
 * SerpAPI's public documentation; not verified against the live API in
 * this repository (tests use a local mock server only, per the Phase 4
 * acceptance rule).
 */
@Serializable
private data class SerpApiResponse(@kotlinx.serialization.SerialName("organic_results") val organicResults: List<SerpApiResult> = emptyList())

@Serializable
private data class SerpApiResult(
    val title: String = "",
    val link: String = "",
    val snippet: String = "",
)

/**
 * The fallback [WebSearchClient] named by the Consumer Roadmap's Phase 4.
 * SerpAPI authenticates via an `api_key` query parameter, not a header, so
 * (like [BraveWebSearchClient]) [RemoteClient]'s built-in bearer-auth hook
 * is left unused here.
 */
class SerpApiWebSearchClient(
    private val apiKey: () -> String?,
    transport: HttpTransport,
    endpoint: String = DEFAULT_BASE_URL,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    requireHttps: Boolean = true,
) : WebSearchClient {
    private val remoteClient = RemoteClient(RemoteEndpoint(endpoint, requireHttps = requireHttps), transport)
    private val json = Json { ignoreUnknownKeys = true }

    override fun search(query: String, count: Int): WebSearchOutcome {
        val key = apiKey() ?: return WebSearchOutcome.Failure("No SerpAPI key configured")
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")

        val result = remoteClient.send(
            path = "search.json?engine=google&q=$encodedQuery&num=$count&api_key=$key",
            headers = mapOf("Accept" to "application/json"),
            retryPolicy = retryPolicy,
        )

        return when (result) {
            is RemoteResult.Success -> parse(result.body)
            is RemoteResult.Failure -> WebSearchOutcome.Failure(result.reason, result.statusCode)
        }
    }

    private fun parse(body: String): WebSearchOutcome = try {
        val response = json.decodeFromString<SerpApiResponse>(body)
        WebSearchOutcome.Success(
            response.organicResults.map {
                WebSearchResult(title = it.title, url = it.link, snippet = it.snippet)
            },
        )
    } catch (e: SerializationException) {
        WebSearchOutcome.Failure("Malformed SerpAPI response: ${e.message}")
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://serpapi.com"
    }
}
