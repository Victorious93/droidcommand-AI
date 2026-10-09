package ai.droidcommand.llm.factory

import ai.droidcommand.config.SecretsVault
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.websearch.BraveWebSearchClient
import ai.droidcommand.websearch.FallbackWebSearchClient
import ai.droidcommand.websearch.SerpApiWebSearchClient
import ai.droidcommand.websearch.WebSearchClient

/** A named `SecretsVault` slot Settings can show a key row for (shared with [WebSearchCatalog] so they cannot drift). */
data class SecretSlot(val label: String, val secretId: String)

/** The search providers chat's web-search toggle can use: Brave primary, SerpAPI fallback (Consumer Roadmap Phase 4). */
object WebSearchCatalog {
    val brave = SecretSlot("Brave Search", "search.brave.api_key")
    val serpApi = SecretSlot("SerpAPI (fallback)", "search.serpapi.api_key")
    val all: List<SecretSlot> = listOf(brave, serpApi)

    /**
     * Keys are read from [vault] on every call, never cached. With no key saved the clients fail cleanly
     * without a network call, so this is safe to build unconditionally.
     */
    fun clientFor(vault: SecretsVault, transport: HttpTransport): WebSearchClient = FallbackWebSearchClient(
        BraveWebSearchClient({ vault.getSecret(brave.secretId)?.takeIf { it.isNotBlank() } }, transport),
        SerpApiWebSearchClient({ vault.getSecret(serpApi.secretId)?.takeIf { it.isNotBlank() } }, transport),
    )
}
