package ai.droidcommand.llm.pchost

import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.openai.OpenAiLlmProvider
import ai.droidcommand.remote.RemoteResult
import ai.droidcommand.remote.isTimeoutFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What went wrong talking to a PC server, in the terms the owner can act on. */
enum class PcHostProblem {
    /** No connection at all: PC off or asleep, wrong address, different network, or the server is not listening. */
    UNREACHABLE,

    /** Connected, then no answer in time. */
    TIMEOUT,

    /** The server wants a key, or rejected the saved one. */
    AUTHENTICATION,

    /** The server does not have the selected model. */
    MODEL_MISSING,

    /** The connection dropped while the reply was arriving. */
    DISCONNECTED,

    /** Something answered, but not the kind of server that was configured. */
    NOT_COMPATIBLE,

    /** The server reported a failure of its own. */
    SERVER_ERROR,

    /** Plain HTTP to a public address; nothing was sent. */
    CLEARTEXT_REFUSED,
}

/**
 * Words for every way a PC server can fail. Each message starts with [PREFIX] so screens can show it unchanged, and
 * each one that is about reaching the PC says so in terms of the PC, never "check your internet": a PC on the same
 * Wi-Fi or VPN answers during an internet outage, and an internet connection does not help when the PC is asleep.
 */
object PcHostErrors {
    const val PREFIX = "PC-hosted model: "

    private const val NOTHING_SAVED = " Nothing was saved; your message is back in the box."
    private const val MAX_SERVER_TEXT = 240

    fun unreachable(address: PcHostAddress, detail: String?): String =
        PREFIX + "could not reach ${address.base}. This is about the path from this phone to your PC, not about internet access. " +
            "Check that the PC is on and awake, that this phone is on the same Wi-Fi or VPN as the PC, and that the server is running " +
            "and listening on the network (not only on the PC itself)." + detailSuffix(detail)

    fun cleartextRefused(reason: String): String = PREFIX + reason

    fun noModel(kind: PcHostKind): String =
        PREFIX + "no model is chosen for ${kind.label}. Open Settings, PC-hosted models, test the connection and pick a model."

    /** Maps a failed probe request (model list, version) to a problem and its message. */
    fun forProbe(kind: PcHostKind, address: PcHostAddress, failure: RemoteResult.Failure, keySaved: Boolean): Pair<PcHostProblem, String> {
        val status = failure.statusCode
        return when {
            status == 401 || status == 403 -> PcHostProblem.AUTHENTICATION to authentication(address, keySaved, serverText(failure.reason))
            status == 404 ->
                PcHostProblem.NOT_COMPATIBLE to PREFIX +
                    "something answered at ${address.base}, but it does not look like ${article(kind)} server (HTTP 404). Check the address and the port."
            status != null -> PcHostProblem.SERVER_ERROR to PREFIX + "the server at ${address.base} answered with an error (HTTP $status)." + detailSuffix(serverText(failure.reason))
            failure.cause.isTimeoutFailure() && !isConnectTimeout(failure.reason) ->
                PcHostProblem.TIMEOUT to PREFIX +
                    "${address.base} accepted the connection but did not answer in time. The PC may be busy or going to sleep. Try again."
            else -> PcHostProblem.UNREACHABLE to unreachable(address, failure.reason)
        }
    }

    fun notCompatible(kind: PcHostKind, address: PcHostAddress): String =
        PREFIX + "something answered at ${address.base}, but the reply is not ${article(kind)} model list. Check the address, the port and the server type."

    /**
     * Maps a failed chat turn. [sawText] is whether any reply text had already arrived, which is the difference
     * between "could not reach the PC" and "lost the PC part-way through".
     */
    fun forChat(
        target: PcHostTarget,
        error: LlmError,
        sawText: Boolean,
        keySaved: Boolean,
        readTimeoutSeconds: Long,
        toolsOffered: Boolean = false,
    ): Pair<PcHostProblem, String> {
        val address = target.address
        val status = STATUS.find(error.message)?.groupValues?.get(1)?.toIntOrNull()
        val server = serverText(error.message)
        return when {
            error is LlmError.Authentication -> PcHostProblem.AUTHENTICATION to authentication(address, keySaved, server) + NOTHING_SAVED
            status == 404 ->
                PcHostProblem.MODEL_MISSING to PREFIX +
                    "the server at ${address.base} does not have the model \"${target.model}\" (HTTP 404). " +
                    missingModelAdvice(target.kind, target.model) + detailSuffix(server) + NOTHING_SAVED
            toolsOffered && status != null && status in TOOL_REFUSAL_STATUSES && server != null && TOOL_REFUSAL.containsMatchIn(server) ->
                PcHostProblem.SERVER_ERROR to PREFIX +
                    "the server at ${address.base} refused a request that offered the helper bot's tools (HTTP $status)." + detailSuffix(server) +
                    " This model or server setup cannot call tools. No tool was run by that request. " +
                    toolRefusalAdvice(target) + NOTHING_SAVED
            status == 502 && target.location == PcModelLocation.CLOUD_BACKED ->
                PcHostProblem.SERVER_ERROR to PREFIX +
                    "your PC answered, but it could not reach the internet service behind the cloud-backed model \"${target.model}\" (HTTP 502). " +
                    "That model needs internet on the PC. A model installed on the PC keeps working without it." + detailSuffix(server) + NOTHING_SAVED
            sawText && (error is LlmError.Network || error is LlmError.Timeout) ->
                PcHostProblem.DISCONNECTED to PREFIX +
                    "the connection to ${address.base} was lost while the reply was arriving (the PC went to sleep, the server stopped, or the " +
                    "phone changed network). The partial reply was discarded." + NOTHING_SAVED
            error is LlmError.Network && error.message == OpenAiLlmProvider.STREAM_ENDED_EARLY ->
                PcHostProblem.DISCONNECTED to PREFIX +
                    "the server at ${address.base} closed the connection before it finished the reply." + NOTHING_SAVED
            error is LlmError.Timeout && !isConnectTimeout(error.message) ->
                PcHostProblem.TIMEOUT to PREFIX +
                    "${address.base} accepted the request but sent nothing for $readTimeoutSeconds seconds. The PC may still be loading a large model, " +
                    "or it went to sleep. Try again; a model that is already loaded answers sooner." + NOTHING_SAVED
            error is LlmError.Network || error is LlmError.Timeout -> PcHostProblem.UNREACHABLE to unreachable(address, error.message) + NOTHING_SAVED
            else ->
                PcHostProblem.SERVER_ERROR to PREFIX +
                    "the server at ${address.base} reported an error${status?.let { " (HTTP $it)" }.orEmpty()}." + detailSuffix(server ?: error.message) + NOTHING_SAVED
        }
    }

    private fun authentication(address: PcHostAddress, keySaved: Boolean, server: String?): String =
        PREFIX + "the server at ${address.base} refused the request as not authorised. " +
            (if (keySaved) "The saved key was not accepted: save the right one in Settings, PC-hosted models." else "It expects an API key: save it in Settings, PC-hosted models.") +
            detailSuffix(server)

    private fun missingModelAdvice(kind: PcHostKind, model: String): String = when (kind) {
        PcHostKind.OLLAMA -> "On the PC run: ollama pull $model. Or pick another model in Settings, PC-hosted models."
        PcHostKind.OPENAI_COMPATIBLE -> "Load that model on the server, or pick another model in Settings, PC-hosted models. If the address has an extra path, check it."
    }

    private fun toolRefusalAdvice(target: PcHostTarget): String = when {
        target.toolsForced -> "Switch off \"Offer tools anyway\" in Settings, PC-hosted models, or pick a model that supports tools."
        target.kind == PcHostKind.OLLAMA -> "Check tool support again in Settings, PC-hosted models, or pick a model that supports tools."
        else -> "llama.cpp's server needs its --jinja option and a tool-capable chat template; check tool support again in Settings, PC-hosted models."
    }

    private fun article(kind: PcHostKind) = if (kind == PcHostKind.OLLAMA) "an Ollama" else "an OpenAI-compatible"

    private fun isConnectTimeout(message: String) = message.contains("connect", ignoreCase = true)

    private fun detailSuffix(detail: String?): String =
        detail?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { " (${it.take(MAX_SERVER_TEXT)})" }.orEmpty()

    /**
     * The server's own words from a `RemoteClient` failure reason ("… status 404: {body}"). Ollama's own API answers
     * `{"error":"text"}`; the OpenAI shape answers `{"error":{"message":"text"}}`. Null when there is no body.
     */
    internal fun serverText(reason: String): String? {
        val body = BODY.find(reason)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return body
        return when (val error = parsed["error"]) {
            is JsonPrimitive -> error.contentOrNull
            is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
            else -> null
        } ?: body
    }

    private val TOOL_REFUSAL_STATUSES = setOf(400, 422, 500, 501)

    /**
     * The two refusals this is written against: Ollama's "<model> does not support tools" (HTTP 400, `server/images.go`
     * `errCapabilities` + `errCapabilityTools`) and llama.cpp's "tools param requires --jinja flag"
     * (`tools/server/server-common.cpp`). Any other error keeps the general wording, even if it mentions tools.
     */
    private val TOOL_REFUSAL = Regex("""does not support\b.*\btools?\b|tools? param requires|tools? (?:are|is) not supported""", RegexOption.IGNORE_CASE)
    private val STATUS = Regex("""(?:status|Server error) (\d{3})""")
    private val BODY = Regex("""(?:status|Server error) \d{3}: (.*)""", RegexOption.DOT_MATCHES_ALL)
}
