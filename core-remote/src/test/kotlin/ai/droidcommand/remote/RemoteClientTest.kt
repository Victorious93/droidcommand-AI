package ai.droidcommand.remote

import ai.droidcommand.agent.RetryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class ScriptedTransport(private val responses: MutableList<HttpResponseSpec>) : HttpTransport {
    val requests = mutableListOf<HttpRequestSpec>()

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        check(responses.isNotEmpty()) { "ScriptedTransport ran out of scripted responses" }
        return responses.removeAt(0)
    }
}

private class ThrowingThenSucceedingTransport(private var failuresBeforeSuccess: Int) : HttpTransport {
    var invocations = 0
        private set

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        invocations++
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--
            throw java.io.IOException("connection reset")
        }
        return HttpResponseSpec(200, emptyMap(), "ok")
    }
}

private fun ok(body: String = "ok") = HttpResponseSpec(200, emptyMap(), body)
private fun serverError() = HttpResponseSpec(503, emptyMap(), "unavailable")
private fun notFound() = HttpResponseSpec(404, emptyMap(), "not found")

class RemoteClientTest {
    private val endpoint = RemoteEndpoint("https://api.example.invalid")

    @Test
    fun `returns Success for a 2xx response without retrying`() {
        val transport = ScriptedTransport(mutableListOf(ok("hello")))
        val client = RemoteClient(endpoint, transport, sleep = { })

        val result = client.send("/v1/ping")

        val success = assertIs<RemoteResult.Success>(result)
        assertEquals(200, success.statusCode)
        assertEquals("hello", success.body)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `retries a 5xx response up to maxAttempts then fails`() {
        val transport = ScriptedTransport(mutableListOf(serverError(), serverError(), serverError()))
        val client = RemoteClient(endpoint, transport, sleep = { })

        val result = client.send("/v1/ping", retryPolicy = RetryPolicy(maxAttempts = 3))

        assertIs<RemoteResult.Failure>(result)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `retries an IOException then succeeds`() {
        val transport = ThrowingThenSucceedingTransport(failuresBeforeSuccess = 2)
        val client = RemoteClient(endpoint, transport, sleep = { })

        val result = client.send("/v1/ping", retryPolicy = RetryPolicy(maxAttempts = 5))

        assertIs<RemoteResult.Success>(result)
        assertEquals(3, transport.invocations)
    }

    @Test
    fun `never retries a 4xx response`() {
        val transport = ScriptedTransport(mutableListOf(notFound()))
        val client = RemoteClient(endpoint, transport, sleep = { })

        val result = client.send("/v1/missing", retryPolicy = RetryPolicy(maxAttempts = 5))

        assertIs<RemoteResult.Failure>(result)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `attaches a bearer Authorization header when authToken is present`() {
        val transport = ScriptedTransport(mutableListOf(ok()))
        val client = RemoteClient(endpoint, transport, authToken = { "secret-token" }, sleep = { })

        client.send("/v1/ping")

        assertEquals("Bearer secret-token", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun `omits the Authorization header when authToken returns null`() {
        val transport = ScriptedTransport(mutableListOf(ok()))
        val client = RemoteClient(endpoint, transport, authToken = { null }, sleep = { })

        client.send("/v1/ping")

        assertNull(transport.requests.single().headers["Authorization"])
    }

    @Test
    fun `reads authToken fresh on every retry attempt, not once at the start`() {
        val transport = ScriptedTransport(mutableListOf(serverError(), ok()))
        var current = "token-1"
        val client = RemoteClient(endpoint, transport, authToken = { current }, sleep = { current = "token-2" })

        client.send("/v1/ping", retryPolicy = RetryPolicy(maxAttempts = 2))

        assertEquals("Bearer token-1", transport.requests[0].headers["Authorization"])
        assertEquals("Bearer token-2", transport.requests[1].headers["Authorization"])
    }

    @Test
    fun `every request URL stays under the configured endpoint host`() {
        val transport = ScriptedTransport(mutableListOf(ok()))
        val client = RemoteClient(endpoint, transport, sleep = { })

        client.send("/v1/ping")

        assertEquals("https://api.example.invalid/v1/ping", transport.requests.single().url)
    }
}
