package ai.droidcommand.websearch

/**
 * The always-present [WebSearchClient]. Same honesty rule as
 * `core-root.NullRootExecutor`/`core-termux.NullTermuxExecutor`/
 * `core-tools-metasploit.NullMetasploitExecutor`: there genuinely is no
 * Brave/SerpAPI key configured, so [search] fails explicitly rather than
 * fabricating an empty-but-successful result set — a caller that checks
 * `is WebSearchOutcome.Success` before trusting an empty list never gets
 * misled into thinking a real search ran and found nothing.
 */
class NullWebSearchClient : WebSearchClient {
    override fun search(query: String, count: Int): WebSearchOutcome =
        WebSearchOutcome.Failure("Cannot search the web for '$query': no web search backend is configured (NullWebSearchClient)")
}
