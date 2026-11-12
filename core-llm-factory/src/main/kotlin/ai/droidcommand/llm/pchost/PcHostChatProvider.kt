package ai.droidcommand.llm.pchost

import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.llm.LlmConfig
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmRequest
import ai.droidcommand.llm.LlmResponse
import ai.droidcommand.llm.StreamingLlmProvider
import ai.droidcommand.llm.openai.OpenAiLlmProvider
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RequestCancellation
import ai.droidcommand.remote.StreamingHttpTransport
import java.io.IOException

/**
 * How long a chat turn waits for a PC. [connectMillis] is short: an address that is going to answer does so quickly,
 * and an asleep PC should be reported in seconds. [readMillis] is the longest silence accepted once connected, and
 * is long because a PC loads a large model into memory before the first word arrives.
 */
data class PcHostTimeouts(val connectMillis: Long = 8_000, val readMillis: Long = 180_000)

/**
 * A chat turn against a PC server. Both kinds are spoken to through the existing [OpenAiLlmProvider]: Ollama serves
 * the OpenAI `/v1/chat/completions` shape itself, so no second chat adapter is needed. What this adds on top:
 * no retry (a repeated request would make the PC generate twice), the PC's own timeouts, a [cancellation] that
 * really closes the connection (the server then stops generating), a check that the stream reached its end marker,
 * and errors reworded by [PcHostErrors].
 */
internal class PcHostChatProvider(
    private val target: PcHostTarget,
    private val key: String?,
    transport: HttpTransport,
    private val timeouts: PcHostTimeouts,
    cancellation: RequestCancellation,
) : StreamingLlmProvider {
    private val delegate = OpenAiLlmProvider(
        LlmConfig(provider = target.kind.id, model = target.model, endpoint = target.address.base, authToken = { key }),
        Stamped(transport, timeouts, cancellation),
        RetryPolicy(maxAttempts = 1),
        requireHttps = false,
        requireStreamEnd = true,
    )

    override val config: LlmConfig get() = delegate.config

    override fun complete(request: LlmRequest): LlmResponse = stream(request) {}

    override fun stream(request: LlmRequest, onTextDelta: (String) -> Unit): LlmResponse {
        var sawText = false
        val response = delegate.stream(request) { delta ->
            sawText = true
            onTextDelta(delta)
        }
        if (response !is LlmResponse.Error) return response
        val (_, message) = PcHostErrors.forChat(target, response.error, sawText, keySaved = key != null, readTimeoutSeconds = timeouts.readMillis / 1_000, toolsOffered = request.tools.isNotEmpty())
        return LlmResponse.Error(response.error.withMessage(message))
    }

    private fun LlmError.withMessage(message: String): LlmError = when (this) {
        is LlmError.Network -> LlmError.Network(message)
        is LlmError.Authentication -> LlmError.Authentication(message)
        is LlmError.Timeout -> LlmError.Timeout(message)
        is LlmError.ModelUnavailable -> LlmError.ModelUnavailable(message)
        is LlmError.InvalidResponse -> LlmError.InvalidResponse(message)
        is LlmError.Cancelled -> LlmError.Cancelled(message)
    }

    /** Puts the PC timeouts and the cancellation on every request the adapter makes. */
    private class Stamped(
        private val delegate: HttpTransport,
        private val timeouts: PcHostTimeouts,
        private val cancellation: RequestCancellation,
    ) : StreamingHttpTransport {
        private fun stamp(request: HttpRequestSpec) = request.copy(
            connectTimeoutMillis = timeouts.connectMillis,
            requestTimeoutMillis = timeouts.readMillis,
            cancellation = cancellation,
        )

        override fun send(request: HttpRequestSpec): HttpResponseSpec = delegate.send(stamp(request))

        override fun sendStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec =
            (delegate as? StreamingHttpTransport ?: throw IOException("This transport cannot stream")).sendStreaming(stamp(request), onLine)
    }
}
