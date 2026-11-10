package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.ToolJsonType
import ai.droidcommand.agent.ToolParameter
import ai.droidcommand.agent.ToolParameterSchema
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.remote.ServerSentEventParser
import ai.droidcommand.remote.isTimeoutFailure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.IOException

/**
 * A small MCP client over the "Streamable HTTP" transport (JSON-RPC 2.0 POSTs, answered with JSON or a short
 * Server-Sent Events stream), built on core-remote's [HttpTransport] so the phone needs no extra network library.
 * Only the tool calls DroidCommand uses are implemented: `initialize`, `notifications/initialized`, `tools/list`
 * (with paging) and `tools/call`. Prompts, resources, sampling and server-to-client requests are not.
 *
 * [token], when it returns a value, is sent as `Authorization: Bearer …`. It is read from the encrypted key
 * storage on every request and never logged or put in a message.
 *
 * Every failure is an [McpException] with plain words: cannot reach, timed out, needs a token, error code,
 * or an answer this app could not read.
 */
class HttpMcpToolSource(
    private val transport: HttpTransport,
    private val url: String,
    private val label: String,
    private val token: () -> String? = { null },
    private val connectTimeoutMillis: Long = 10_000,
    private val requestTimeoutMillis: Long = 30_000,
) : McpToolSource {
    private val lock = Any()
    private var sessionId: String? = null
    private var protocolVersion: String = PROTOCOL_VERSION
    private var initialized = false
    private var nextId = 1

    override fun listTools(): List<McpToolInfo> = synchronized(lock) {
        val tools = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = buildJsonObject { cursor?.let { put("cursor", it) } }
            val result = request("tools/list", params)
            val list = result["tools"] as? JsonArray ?: throw unreadable()
            list.forEach { element -> parseTool(element)?.let { tools += it } }
            cursor = (result["nextCursor"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            pages++
        } while (cursor != null && pages < MAX_PAGES)
        tools
    }

    override fun call(name: String, arguments: Map<String, String>): ToolResult = synchronized(lock) {
        val schema = schemaFor(name)
        val params = buildJsonObject {
            put("name", name)
            put("arguments", JsonObject(arguments.mapValues { (key, value) -> typed(value, schema[key]) }))
        }
        val result = request("tools/call", params)
        val text = textOf(result)
        if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) ToolResult.Failure(text) else ToolResult.Success(text)
    }

    override fun close() = synchronized(lock) {
        val id = sessionId
        sessionId = null
        initialized = false
        if (id != null) {
            // Polite session end; the server may not support it, and that is fine.
            try {
                transport.send(
                    HttpRequestSpec("DELETE", url, baseHeaders(id), null, connectTimeoutMillis, SHORT_TIMEOUT_MILLIS),
                )
            } catch (_: Exception) {
            }
        }
    }

    // Input types from the last tools/list, so "3" can be sent as the number 3 when the server asked for a number.
    private val knownTypes = mutableMapOf<String, Map<String, ToolJsonType>>()

    private fun schemaFor(name: String): Map<String, ToolJsonType> = knownTypes[name].orEmpty()

    private fun parseTool(element: JsonElement): McpToolInfo? {
        val obj = element as? JsonObject ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val annotations = obj["annotations"] as? JsonObject
        val schema = parseSchema(obj["inputSchema"] as? JsonObject)
        knownTypes[name] = schema?.parameters?.associate { it.name to it.type }.orEmpty()
        return McpToolInfo(
            name = name,
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            readOnlyHint = (annotations?.get("readOnlyHint") as? JsonPrimitive)?.booleanOrNull,
            destructiveHint = (annotations?.get("destructiveHint") as? JsonPrimitive)?.booleanOrNull,
            parameters = schema,
        )
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        ensureInitialized()
        return try {
            post(method, params)
        } catch (e: SessionExpired) {
            // The server forgot our session (HTTP 404): start a new one once, then retry.
            initialized = false
            sessionId = null
            ensureInitialized()
            try {
                post(method, params)
            } catch (_: SessionExpired) {
                throw McpException("$label keeps ending the connection. Try again later.")
            }
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put(
                "clientInfo",
                buildJsonObject {
                    put("name", CLIENT_NAME)
                    put("version", CLIENT_VERSION)
                },
            )
        }
        val result = try {
            post("initialize", params, captureSession = true)
        } catch (_: SessionExpired) {
            throw McpException("$label is not an MCP server at that address (it answered 404).")
        }
        (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.let { protocolVersion = it }
        val note = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        }
        // A notification gets 202 Accepted with no body; any 2xx is fine, an error here is not worth failing on.
        try {
            transport.send(
                HttpRequestSpec("POST", url, baseHeaders(sessionId), note.toString(), connectTimeoutMillis, requestTimeoutMillis),
            )
        } catch (e: Exception) {
            throw networkFailure(e)
        }
        initialized = true
    }

    private class SessionExpired : Exception()

    private fun post(method: String, params: JsonObject, captureSession: Boolean = false): JsonObject {
        val id = nextId++
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = try {
            transport.send(
                HttpRequestSpec("POST", url, baseHeaders(sessionId), body.toString(), connectTimeoutMillis, requestTimeoutMillis),
            )
        } catch (e: Exception) {
            throw networkFailure(e)
        }
        when (response.statusCode) {
            in 200..299 -> Unit
            401, 403 -> throw McpException("$label needs a valid token. Add or update it in Settings.")
            404 -> throw SessionExpired()
            in 300..399 -> throw McpException("$label tried to send this app somewhere else. Check the address.")
            else -> throw McpException("$label answered with error ${response.statusCode}.")
        }
        if (captureSession) sessionId = header(response, "Mcp-Session-Id")?.takeIf { it.isNotBlank() }
        val message = findResponse(response, id) ?: throw unreadable()
        (message["error"] as? JsonObject)?.let { error ->
            val text = (error["message"] as? JsonPrimitive)?.contentOrNull?.take(300).orEmpty()
            throw McpException("$label refused: ${text.ifBlank { "no reason given" }}")
        }
        return message["result"] as? JsonObject ?: throw unreadable()
    }

    /** The JSON-RPC response with [id], from a plain JSON body or from an SSE stream of messages. */
    private fun findResponse(response: HttpResponseSpec, id: Int): JsonObject? {
        val contentType = header(response, "Content-Type").orEmpty().lowercase()
        val body = response.body
        val candidates = if (contentType.contains("text/event-stream") || body.trimStart().startsWith("event:") || body.trimStart().startsWith("data:")) {
            val events = mutableListOf<String>()
            val parser = ServerSentEventParser { events += it.data }
            body.lineSequence().forEach { parser.feed(it.removeSuffix("\r")) }
            parser.finish()
            events.mapNotNull { parseJson(it) }
        } else {
            listOfNotNull(parseJson(body))
        }
        return candidates.flatMap { if (it is JsonArray) it.toList() else listOf(it) }
            .filterIsInstance<JsonObject>()
            .firstOrNull { (it["id"] as? JsonPrimitive)?.intOrNull == id }
    }

    private fun parseJson(text: String): JsonElement? = try {
        if (text.isBlank()) null else json.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

    private fun baseHeaders(session: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "application/json, text/event-stream")
        if (initialized || session != null) put("MCP-Protocol-Version", protocolVersion)
        session?.let { put("Mcp-Session-Id", it) }
        token()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("Authorization", "Bearer $it") }
    }

    private fun networkFailure(e: Exception): McpException = when {
        e.isTimeoutFailure() -> McpException("$label did not answer in time. Check that it is running.", e)
        e is IOException -> McpException("Could not reach $label. Check the address and that it is running.", e)
        else -> McpException("Could not talk to $label.", e)
    }

    private fun unreadable() = McpException("$label sent an answer this app could not read.")

    private fun header(response: HttpResponseSpec, name: String): String? =
        response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val CLIENT_NAME = "droidcommand-ai"
        const val CLIENT_VERSION = "0.1.0"
        private const val MAX_PAGES = 5
        private const val SHORT_TIMEOUT_MILLIS = 3_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** The text of a `tools/call` result: its text blocks, or its structured content, or a note for other kinds. */
        internal fun textOf(result: JsonObject): String {
            val blocks = (result["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val parts = blocks.map { block ->
                when ((block["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text" -> (block["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    "image" -> "[image]"
                    "audio" -> "[audio]"
                    "resource", "resource_link" -> "[file: " + (
                        ((block["resource"] as? JsonObject)?.get("uri") ?: block["uri"]) as? JsonPrimitive
                    )?.contentOrNull.orEmpty() + "]"
                    else -> ""
                }
            }.filter { it.isNotEmpty() }
            if (parts.isNotEmpty()) return parts.joinToString("\n")
            return result["structuredContent"]?.takeIf { it !is JsonNull }?.toString().orEmpty()
        }

        /** An MCP `inputSchema` (JSON Schema object) as a [ToolParameterSchema]; null when it has no properties. */
        fun parseSchema(schema: JsonObject?): ToolParameterSchema? {
            val properties = schema?.get("properties") as? JsonObject ?: return null
            if (properties.isEmpty()) return null
            val required = (schema["required"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            val params = properties.entries.mapNotNull { (name, value) ->
                if (name.isBlank()) return@mapNotNull null
                val prop = value as? JsonObject
                val type = when ((prop?.get("type") as? JsonPrimitive)?.contentOrNull) {
                    "number", "integer" -> ToolJsonType.NUMBER
                    "boolean" -> ToolJsonType.BOOLEAN
                    "array" -> ToolJsonType.ARRAY
                    "object" -> ToolJsonType.OBJECT
                    else -> ToolJsonType.STRING
                }
                ToolParameter(
                    name = name,
                    type = type,
                    description = (prop?.get("description") as? JsonPrimitive)?.contentOrNull.orEmpty().take(300),
                    required = name in required,
                )
            }
            return ToolParameterSchema(params)
        }

        /** [value] as the JSON type the server asked for, when it parses as that type; otherwise as a string. */
        internal fun typed(value: String, type: ToolJsonType?): JsonElement = when (type) {
            ToolJsonType.NUMBER -> value.trim().toLongOrNull()?.let { JsonPrimitive(it) }
                ?: value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
                ?: JsonPrimitive(value)
            ToolJsonType.BOOLEAN -> when (value.trim().lowercase()) {
                "true" -> JsonPrimitive(true)
                "false" -> JsonPrimitive(false)
                else -> JsonPrimitive(value)
            }
            ToolJsonType.ARRAY, ToolJsonType.OBJECT -> try {
                json.parseToJsonElement(value).takeIf { if (type == ToolJsonType.ARRAY) it is JsonArray else it is JsonObject }
                    ?: JsonPrimitive(value)
            } catch (_: Exception) {
                JsonPrimitive(value)
            }
            else -> JsonPrimitive(value)
        }
    }
}
