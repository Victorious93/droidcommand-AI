package ai.droidcommand.llm.pchost

import ai.droidcommand.agent.RetryPolicy
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.RemoteClient
import ai.droidcommand.remote.RemoteEndpoint
import ai.droidcommand.remote.RemoteResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.InetAddress
import java.net.URI

/** Outcome of [PcHostClient.probe]. */
sealed class PcHostProbe {
    /** The server answered as the expected kind. [models] may be empty (nothing installed or loaded yet). */
    data class Reachable(val serverVersion: String?, val models: List<PcHostModel>) : PcHostProbe()

    data class Failed(val problem: PcHostProblem, val message: String) : PcHostProbe()
}

/**
 * What a PC server said about one model just before a request is routed to it. Every field is the server's own
 * answer, never a guess: a null means the server did not say.
 *
 * [problem] is non-null when the server could not be reached or does not have the model; nothing should be sent then.
 * [toolCalling] is whether the model can be offered tools. [contextTokens] is the window requests are served with,
 * and [contextSource] says where that number came from. [location] is where the model runs.
 */
data class PcModelFacts(
    val problem: String? = null,
    val toolCalling: Boolean? = null,
    val toolCallingDetail: String = "",
    val contextTokens: Int? = null,
    val contextSource: String? = null,
    val location: PcModelLocation = PcModelLocation.UNKNOWN,
    val upstream: String? = null,
) {
    val reachable: Boolean get() = problem == null
}

/**
 * "Test connection" and model discovery for a PC server. One short request, no retries, short timeouts: the answer
 * to "is my PC reachable right now" should come back in seconds either way.
 *
 * Ollama is asked through its own API: `GET /api/tags` lists the models, and marks a model the PC only forwards to
 * another host with `remote_host`/`remote_model`; `GET /api/version` gives the version. An OpenAI-compatible server
 * is asked `GET /v1/models`, which lists ids and says nothing about where a model runs. A saved key is sent as
 * `Authorization: Bearer`, the form both document.
 */
class PcHostClient(
    private val transport: HttpTransport,
    private val connectTimeoutMillis: Long = 5_000,
    private val requestTimeoutMillis: Long = 10_000,
    private val resolve: ((String) -> List<InetAddress>)? = null,
) {
    fun probe(kind: PcHostKind, address: PcHostAddress, key: String?): PcHostProbe {
        val refused = if (resolve == null) address.cleartextProblem() else address.cleartextProblem(resolve)
        if (refused != null) return PcHostProbe.Failed(PcHostProblem.CLEARTEXT_REFUSED, PcHostErrors.cleartextRefused(refused))
        val token = key?.takeIf { it.isNotBlank() }
        val client = RemoteClient(RemoteEndpoint(address.base, requireHttps = false), transport, authToken = { token })
        val listPath = if (kind == PcHostKind.OLLAMA) "api/tags" else "v1/models"
        val body = when (val result = get(client, listPath)) {
            is RemoteResult.Failure -> {
                val (problem, message) = PcHostErrors.forProbe(kind, address, result, keySaved = token != null)
                return PcHostProbe.Failed(problem, message)
            }
            is RemoteResult.Success -> result.body
        }
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val models = when (kind) {
            PcHostKind.OLLAMA -> (root?.get("models") as? JsonArray)?.let(::ollamaModels)
            PcHostKind.OPENAI_COMPATIBLE -> (root?.get("data") as? JsonArray)?.let(::openAiModels)
        } ?: return PcHostProbe.Failed(PcHostProblem.NOT_COMPATIBLE, PcHostErrors.notCompatible(kind, address))
        val version = if (kind == PcHostKind.OLLAMA) ollamaVersion(client) else null
        return PcHostProbe.Reachable(version, models)
    }

    /**
     * Asks the server about [target]'s model right before a turn is routed to it: is the server reachable, can the
     * model call tools, how large is its window, and does it run on the PC. One or two short requests, no retries.
     *
     * Ollama: `POST /api/show` (`capabilities` lists `tools` for a tool-capable model; `remote_host`/`remote_model`
     * mark a model the PC forwards elsewhere; `parameters` may carry `num_ctx`; `model_info` carries the trained
     * `*.context_length`) and `GET /api/ps` (`context_length` of a model that is loaded now). When none of those give
     * the served window, Ollama's documented default of 4,096 tokens is used, capped at the trained length, and
     * [PcModelFacts.contextSource] says it is the default.
     *
     * OpenAI-compatible: llama.cpp's `GET /props` when the server has it (`default_generation_settings.n_ctx`,
     * `chat_template_caps.supports_tools`, and `model_path`, a file on the PC, which is what establishes that the
     * model runs there). Any other OpenAI-compatible server is only checked for reachability (`GET /v1/models`); it
     * reports neither tool support, window nor location, and the facts say so.
     */
    fun inspect(target: PcHostTarget, key: String?): PcModelFacts {
        val address = target.address
        val refused = if (resolve == null) address.cleartextProblem() else address.cleartextProblem(resolve)
        if (refused != null) return PcModelFacts(problem = PcHostErrors.cleartextRefused(refused))
        val token = key?.takeIf { it.isNotBlank() }
        val client = RemoteClient(RemoteEndpoint(address.base, requireHttps = false), transport, authToken = { token })
        return when (target.kind) {
            PcHostKind.OLLAMA -> inspectOllama(client, target, token != null)
            PcHostKind.OPENAI_COMPATIBLE -> inspectOpenAiCompatible(client, target, token != null)
        }
    }

    private fun inspectOllama(client: RemoteClient, target: PcHostTarget, keySaved: Boolean): PcModelFacts {
        val shown = client.send(
            path = "api/show",
            method = "POST",
            headers = mapOf("content-type" to "application/json"),
            body = JsonObject(mapOf("model" to JsonPrimitive(target.model))).toString(),
            retryPolicy = RetryPolicy(maxAttempts = 1),
            connectTimeoutMillis = connectTimeoutMillis,
            requestTimeoutMillis = requestTimeoutMillis,
        )
        val body = when (shown) {
            is RemoteResult.Failure -> {
                if (shown.statusCode == 404) {
                    return PcModelFacts(
                        problem = PcHostErrors.PREFIX + "${target.address.base} is reachable but does not have the model \"${target.model}\". " +
                            "On the PC run: ollama pull ${target.model}",
                    )
                }
                return PcModelFacts(problem = PcHostErrors.forProbe(target.kind, target.address, shown, keySaved).second)
            }
            is RemoteResult.Success -> shown.body
        }
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return PcModelFacts(problem = PcHostErrors.notCompatible(target.kind, target.address))
        val capabilities = (root["capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val remoteHost = root.text("remote_host")
        val remote = remoteHost != null || root.text("remote_model") != null || hasCloudTag(target.model) ||
            target.location == PcModelLocation.CLOUD_BACKED
        val trained = (root["model_info"] as? JsonObject)?.entries
            ?.firstOrNull { it.key.endsWith(".context_length") }?.let { (it.value as? JsonPrimitive)?.longOrNull?.toInt() }
        val numCtx = root.text("parameters")?.lineSequence()
            ?.firstNotNullOfOrNull { NUM_CTX.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
        val running = (get(client, "api/ps") as? RemoteResult.Success)?.body
            ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?.let { it["models"] as? JsonArray }
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.text("name") == target.model || it.text("model") == target.model }
            ?.let { (it["context_length"] as? JsonPrimitive)?.longOrNull?.toInt() }
            ?.takeIf { it > 0 }
        val (context, source) = when {
            remote -> null to null
            running != null -> running to "the loaded model's context length, reported by Ollama"
            numCtx != null -> numCtx to "the model's num_ctx parameter"
            else -> minOf(OLLAMA_DEFAULT_CONTEXT, trained ?: OLLAMA_DEFAULT_CONTEXT) to
                "Ollama's default (the model is not loaded, so the server reported no live value; OLLAMA_CONTEXT_LENGTH on the PC changes it)"
        }
        return PcModelFacts(
            toolCalling = capabilities?.contains("tools"),
            toolCallingDetail = when {
                capabilities == null -> "this Ollama version does not list model capabilities"
                "tools" in capabilities -> "Ollama lists tools for this model"
                else -> "Ollama lists only ${capabilities.joinToString(", ").ifBlank { "no capabilities" }} for this model"
            },
            contextTokens = context,
            contextSource = source,
            location = if (remote) PcModelLocation.CLOUD_BACKED else PcModelLocation.SERVER_LOCAL,
            upstream = if (remote) hostOf(remoteHost) ?: target.upstream ?: OLLAMA_CLOUD else null,
        )
    }

    private fun inspectOpenAiCompatible(client: RemoteClient, target: PcHostTarget, keySaved: Boolean): PcModelFacts {
        val props = get(client, "props")
        val root = (props as? RemoteResult.Success)?.body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val settings = root?.get("default_generation_settings") as? JsonObject
        if (root == null || settings == null) {
            // Not llama.cpp (or /props is off). Reachability still has to be established before anything is sent.
            if (props is RemoteResult.Failure && props.statusCode == null) {
                return PcModelFacts(problem = PcHostErrors.forProbe(target.kind, target.address, props, keySaved).second)
            }
            val listed = get(client, "v1/models")
            if (listed is RemoteResult.Failure) {
                return PcModelFacts(problem = PcHostErrors.forProbe(target.kind, target.address, listed, keySaved).second)
            }
            return PcModelFacts(toolCallingDetail = "this server does not report whether the model can call tools")
        }
        val caps = root["chat_template_caps"] as? JsonObject
        val tools = caps?.let { (it["supports_tools"] as? JsonPrimitive)?.contentOrNull == "true" && (it["supports_tool_calls"] as? JsonPrimitive)?.contentOrNull != "false" }
        val modelPath = root.text("model_path")?.takeIf { it != "none" }
        return PcModelFacts(
            toolCalling = tools,
            toolCallingDetail = when (tools) {
                true -> "the llama.cpp server reports a tool-capable chat template"
                false -> "the llama.cpp server reports that this model's chat template has no tool support"
                null -> "this llama.cpp server does not report chat template capabilities"
            },
            contextTokens = (settings["n_ctx"] as? JsonPrimitive)?.longOrNull?.toInt()?.takeIf { it > 0 },
            contextSource = "the llama.cpp server's n_ctx",
            // A llama.cpp server runs the model file it reports; that is the only evidence of location this API offers.
            location = if (modelPath != null) PcModelLocation.SERVER_LOCAL else PcModelLocation.UNKNOWN,
        )
    }

    private fun get(client: RemoteClient, path: String): RemoteResult = client.send(
        path = path,
        retryPolicy = RetryPolicy(maxAttempts = 1),
        connectTimeoutMillis = connectTimeoutMillis,
        requestTimeoutMillis = requestTimeoutMillis,
    )

    /** Best effort: a server that lists models but has no version endpoint is still reachable. */
    private fun ollamaVersion(client: RemoteClient): String? {
        val body = (get(client, "api/version") as? RemoteResult.Success)?.body ?: return null
        return runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.text("version") }.getOrNull()
    }

    private fun ollamaModels(entries: JsonArray): List<PcHostModel> = entries.mapNotNull { entry ->
        val model = entry as? JsonObject ?: return@mapNotNull null
        val name = model.text("name") ?: model.text("model") ?: return@mapNotNull null
        val remoteHost = model.text("remote_host")
        val remote = remoteHost != null || model.text("remote_model") != null || hasCloudTag(name)
        val details = model["details"] as? JsonObject
        val size = (model["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 && !remote }
        val detail = listOfNotNull(details?.text("parameter_size"), details?.text("quantization_level"), size?.let(::gigabytes))
            .joinToString(" · ").ifBlank { null }
        PcHostModel(
            id = name,
            location = if (remote) PcModelLocation.CLOUD_BACKED else PcModelLocation.SERVER_LOCAL,
            upstream = if (remote) hostOf(remoteHost) ?: OLLAMA_CLOUD else null,
            detail = detail,
        )
    }

    private fun openAiModels(entries: JsonArray): List<PcHostModel> = entries.mapNotNull { entry ->
        val model = entry as? JsonObject ?: return@mapNotNull null
        val id = model.text("id") ?: return@mapNotNull null
        PcHostModel(id, PcModelLocation.UNKNOWN, detail = model.text("owned_by")?.let { "owner: $it" })
    }

    companion object {
        private const val OLLAMA_CLOUD = "ollama.com"
        private const val BYTES_PER_GB = 1_000_000_000.0

        /** Ollama's default context length, as recorded from its FAQ in docs/PC_HOSTED_MODELS.md. */
        const val OLLAMA_DEFAULT_CONTEXT = 4_096
        private val NUM_CTX = Regex("""^num_ctx\s+(\d+)$""")

        /**
         * Ollama names its cloud models with a `cloud` tag (`gemma4:cloud`, `gpt-oss:120b-cloud`). Used in addition to
         * the `remote_host`/`remote_model` fields, so a server version that omits those still shows the model as cloud-backed.
         */
        internal fun hasCloudTag(name: String): Boolean {
            val tag = name.substringAfterLast(':', "")
            return tag == "cloud" || tag.endsWith("-cloud")
        }

        private fun hostOf(url: String?): String? =
            url?.let { runCatching { URI(it).host }.getOrNull() ?: it }?.takeIf { it.isNotBlank() }

        private fun gigabytes(bytes: Long): String = "%.1f GB".format(java.util.Locale.ROOT, bytes / BYTES_PER_GB)

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
