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
