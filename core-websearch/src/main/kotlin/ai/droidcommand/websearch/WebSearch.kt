package ai.droidcommand.websearch

/**
 * One search-result hit, already normalized away from any one provider's
 * JSON shape — the same "provider-independent contract" pattern
 * `core-llm`'s `LlmRequest`/`LlmResponse` use to keep callers from ever
 * depending on Brave's or SerpAPI's wire format directly.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
)

/** The outcome of a [WebSearchClient.search] call. */
sealed class WebSearchOutcome {
    data class Success(val results: List<WebSearchResult>) : WebSearchOutcome()

    /**
     * [statusCode] is null for a failure that never reached an HTTP
     * response (I/O error, timeout) and set to the response's status when
     * it did — the same distinction `core-remote.RemoteResult.Failure`
     * already draws, kept here rather than re-derived by every caller.
     */
    data class Failure(val reason: String, val statusCode: Int? = null) : WebSearchOutcome()
}

/**
 * The seam Phase 4's chat-toolbar web-search toggle calls through — one
 * provider-agnostic contract, so the UI (and `FallbackWebSearchClient`
 * below) never need to know which concrete provider answered.
 */
interface WebSearchClient {
    fun search(query: String, count: Int = 5): WebSearchOutcome
}

/**
 * Tries [primary] first; only on a [WebSearchOutcome.Failure] does it ask
 * [fallback] — matching the Consumer Roadmap's own "Brave Search primary,
 * SerpAPI fallback" wording. A successful empty result list (a query with
 * no hits) is still a [WebSearchOutcome.Success] and is never treated as a
 * reason to fail over.
 */
class FallbackWebSearchClient(
    private val primary: WebSearchClient,
    private val fallback: WebSearchClient,
) : WebSearchClient {
    override fun search(query: String, count: Int): WebSearchOutcome {
        val primaryResult = primary.search(query, count)
        return if (primaryResult is WebSearchOutcome.Success) primaryResult else fallback.search(query, count)
    }
}
