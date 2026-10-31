package ai.droidcommand.n8n

import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException

sealed class N8nOutcome<out T> {
    data class Ok<T>(val value: T, val words: String) : N8nOutcome<T>()
    data class Failed(val words: String) : N8nOutcome<Nothing>()
}

/**
 * Talks to a self-hosted n8n over its public REST API.
 *
 * List/test calls send `X-N8N-API-KEY`. Webhook triggers do **not** — the
 * path is the secret, and echoing the API key to a webhook would leak it.
 */
class N8nClient(
    private val transport: HttpTransport,
    private val apiKey: () -> String?,
) {
    fun testConnection(url: N8nServerUrl): N8nOutcome<Int> {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return N8nOutcome.Failed("Save an API key first. It is stored encrypted on this phone.")
        return when (val listed = listWorkflows(url)) {
            is N8nOutcome.Ok -> N8nOutcome.Ok(listed.value.size, N8nResultWords.connection(200, listed.value.size))
            is N8nOutcome.Failed -> listed
        }
    }

    fun listWorkflows(url: N8nServerUrl): N8nOutcome<List<N8nWorkflow>> {
        val key = apiKey()?.trim().orEmpty()
        if (key.isEmpty()) return N8nOutcome.Failed("Save an API key first. It is stored encrypted on this phone.")
        val collected = mutableListOf<N8nWorkflow>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val path = buildString {
                append("api/v1/workflows?limit=$PAGE_SIZE")
                if (!cursor.isNullOrBlank()) append("&cursor=").append(cursor)
            }
            val response = send(url.api(path), "GET", apiHeaders(key), null) ?: return N8nOutcome.Failed(
                N8nResultWords.connection(0, null),
            )
            if (response.status !in 200..299) {
                return N8nOutcome.Failed(N8nResultWords.connection(response.status, null, N8nResultWords.describe(response.status, response.body, "n8n")))
            }
            val page = runCatching { json.decodeFromString<WorkflowListJson>(response.body) }.getOrElse {
                return N8nOutcome.Failed("n8n sent a reply this app could not read.")
            }
            collected += page.data.mapNotNull { it.toWorkflow() }
            cursor = page.nextCursor?.takeIf { it.isNotBlank() }
            if (cursor == null) {
                return N8nOutcome.Ok(collected.toList(), N8nResultWords.connection(200, collected.size))
            }
        }
        return N8nOutcome.Ok(collected.toList(), N8nResultWords.connection(200, collected.size))
    }

    fun triggerWebhook(url: N8nServerUrl, workflow: N8nWorkflow, jsonBody: String): N8nOutcome<String> {
        val webhook = workflow.webhook
            ?: return N8nOutcome.Failed("${workflow.name} has no webhook, so this app cannot start it.")
        if (!workflow.active) {
            return N8nOutcome.Failed("${workflow.name} is switched off in n8n. Turn it on there, then try again.")
        }
        val method = webhook.httpMethod.uppercase()
        val payload = normalizeJsonObject(jsonBody) ?: return N8nOutcome.Failed("The JSON must be an object, like {\"name\":\"Don\"}.")
        if (method == "GET" || method == "HEAD") {
            return N8nOutcome.Failed("${workflow.name} uses $method. This screen only sends JSON with POST, PUT, or PATCH.")
        }
        val response = send(
            url.webhook(webhook.path),
            method,
            mapOf("Accept" to "application/json", "Content-Type" to "application/json"),
            payload,
        ) ?: return N8nOutcome.Failed("Could not reach n8n to run ${workflow.name}.")
        val words = N8nResultWords.describe(response.status, response.body, workflow.name)
        return if (response.status in 200..299) N8nOutcome.Ok(words, words) else N8nOutcome.Failed(words)
    }

    private fun send(url: String, method: String, headers: Map<String, String>, body: String?): HttpReply? {
        return try {
            val spec = transport.send(
                HttpRequestSpec(
                    method = method,
                    url = url,
                    headers = headers,
                    body = body,
                    connectTimeoutMillis = 10_000,
                    requestTimeoutMillis = 30_000,
                ),
            )
            HttpReply(spec.statusCode, spec.body)
        } catch (_: IOException) {
            null
        }
    }

    private fun apiHeaders(key: String): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "X-N8N-API-KEY" to key,
    )

    private data class HttpReply(val status: Int, val body: String)

    companion object {
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 5
        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        internal fun normalizeJsonObject(raw: String): String? {
            val trimmed = raw.trim().ifEmpty { "{}" }
            val element = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
            return if (element is JsonObject) trimmed else null
        }
    }
}

@Serializable
private data class WorkflowListJson(
    val data: List<WorkflowJson> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
private data class WorkflowJson(
    val id: String? = null,
    val name: String? = null,
    val active: Boolean = false,
    val nodes: List<NodeJson> = emptyList(),
) {
    fun toWorkflow(): N8nWorkflow? {
        val id = id?.trim().orEmpty()
        if (id.isEmpty()) return null
        return N8nWorkflow(
            id = id,
            name = name?.trim().orEmpty().ifEmpty { "Untitled workflow" },
            active = active,
            webhook = nodes.firstNotNullOfOrNull { it.toWebhook() },
        )
    }
}

@Serializable
private data class NodeJson(
    val type: String? = null,
    val parameters: JsonObject? = null,
) {
    fun toWebhook(): N8nWebhook? {
        val type = type.orEmpty()
        if (!type.contains("webhook", ignoreCase = true)) return null
        if (type.contains("respondToWebhook", ignoreCase = true)) return null
        val path = (parameters?.get("path") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (path.isEmpty()) return null
        val method = (parameters?.get("httpMethod") as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } ?: "POST"
        return N8nWebhook(path = path, httpMethod = method)
    }
}
