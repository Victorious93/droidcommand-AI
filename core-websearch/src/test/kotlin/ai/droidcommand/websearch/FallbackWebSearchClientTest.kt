package ai.droidcommand.websearch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class StubWebSearchClient(private val outcome: WebSearchOutcome) : WebSearchClient {
    var callCount = 0
        private set

    override fun search(query: String, count: Int): WebSearchOutcome {
        callCount++
        return outcome
    }
}

class FallbackWebSearchClientTest {
    private val result = WebSearchResult("t", "https://example.com", "s")

    @Test
    fun `primary success is returned without calling fallback`() {
        val primary = StubWebSearchClient(WebSearchOutcome.Success(listOf(result)))
        val fallback = StubWebSearchClient(WebSearchOutcome.Failure("should never be called"))

        val outcome = assertIs<WebSearchOutcome.Success>(FallbackWebSearchClient(primary, fallback).search("q"))
        assertEquals(listOf(result), outcome.results)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `primary failure falls over to fallback`() {
        val primary = StubWebSearchClient(WebSearchOutcome.Failure("primary down"))
        val fallback = StubWebSearchClient(WebSearchOutcome.Success(listOf(result)))

        val outcome = assertIs<WebSearchOutcome.Success>(FallbackWebSearchClient(primary, fallback).search("q"))
        assertEquals(listOf(result), outcome.results)
        assertEquals(1, fallback.callCount)
    }

    @Test
    fun `an empty result list from primary is still success, not a fallback trigger`() {
        val primary = StubWebSearchClient(WebSearchOutcome.Success(emptyList()))
        val fallback = StubWebSearchClient(WebSearchOutcome.Failure("should never be called"))

        val outcome = assertIs<WebSearchOutcome.Success>(FallbackWebSearchClient(primary, fallback).search("q"))
        assertEquals(emptyList(), outcome.results)
        assertEquals(0, fallback.callCount)
    }

    @Test
    fun `both failing returns the fallback's failure`() {
        val primary = StubWebSearchClient(WebSearchOutcome.Failure("primary down"))
        val fallback = StubWebSearchClient(WebSearchOutcome.Failure("fallback down too"))

        val outcome = assertIs<WebSearchOutcome.Failure>(FallbackWebSearchClient(primary, fallback).search("q"))
        assertEquals("fallback down too", outcome.reason)
    }
}
