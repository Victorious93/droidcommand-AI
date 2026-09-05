package ai.droidcommand.remote

import ai.droidcommand.agent.RetryPolicy
import java.io.IOException

sealed class RemoteResult {
    data class Success(val statusCode: Int, val body: String, val headers: Map<String, String>) : RemoteResult()

    /**
     * [statusCode] is null when the failure never reached an HTTP response
     * (a connection error or timeout) and set to the response's status when
     * it did, so a caller can tell "the server said no" from "the network
     * never delivered a response" without re-parsing [reason].
     */
    data class Failure(val reason: String, val cause: Throwable? = null, val statusCode: Int? = null) : RemoteResult()
}

/**
 * Sends a request to [endpoint] through [transport], attaching
 * [authToken]'s current value as a bearer `Authorization` header — read
 * fresh on every attempt, never cached, matching the pattern already used
 * by core-llm's `LlmConfig` and core-config's `LlmConfigLoader`. Transient
 * failures (an I/O error/timeout, or a 5xx status) are retried under
 * [RetryPolicy], reused from core-agent rather than reimplemented. A 4xx
 * response is never retried — it means the request itself was wrong, and
 * retrying it would just repeat the same failure against the server.
 */
class RemoteClient(
    private val endpoint: RemoteEndpoint,
    private val transport: HttpTransport,
    private val authToken: () -> String? = { null },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun send(
        path: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        retryPolicy: RetryPolicy = RetryPolicy(),
        connectTimeoutMillis: Long = 10_000,
        requestTimeoutMillis: Long = 30_000,
    ): RemoteResult {
        val url = endpoint.resolve(path)
        var lastResult: RemoteResult.Failure = RemoteResult.Failure("Request never attempted")

        for (attempt in 1..retryPolicy.maxAttempts) {
            val spec = HttpRequestSpec(
                method = method,
                url = url,
                headers = headers + authHeader(),
                body = body,
                connectTimeoutMillis = connectTimeoutMillis,
                requestTimeoutMillis = requestTimeoutMillis,
            )

            val outcome = try {
                classify(transport.send(spec))
            } catch (e: IOException) {
                Outcome.Retryable(RemoteResult.Failure(e.message ?: "I/O failure", e))
            }

            when (outcome) {
                is Outcome.Success -> return outcome.result
                is Outcome.Terminal -> return outcome.result
                is Outcome.Retryable -> {
                    lastResult = outcome.result
                    if (attempt < retryPolicy.maxAttempts) {
                        sleep(retryPolicy.backoff(attempt))
                    }
                }
            }
        }
        return lastResult
    }

    private fun classify(response: HttpResponseSpec): Outcome = when {
        response.statusCode in 200..399 ->
            Outcome.Success(RemoteResult.Success(response.statusCode, response.body, response.headers))

        response.statusCode in 500..599 ->
            Outcome.Retryable(RemoteResult.Failure("Server error ${response.statusCode}: ${response.body}", statusCode = response.statusCode))

        else ->
            Outcome.Terminal(RemoteResult.Failure("Request failed with status ${response.statusCode}: ${response.body}", statusCode = response.statusCode))
    }

    private fun authHeader(): Map<String, String> =
        authToken()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    private sealed class Outcome {
        data class Success(val result: RemoteResult.Success) : Outcome()
        data class Retryable(val result: RemoteResult.Failure) : Outcome()
        data class Terminal(val result: RemoteResult.Failure) : Outcome()
    }
}
