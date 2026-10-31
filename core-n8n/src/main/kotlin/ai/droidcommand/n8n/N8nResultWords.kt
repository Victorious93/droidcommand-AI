package ai.droidcommand.n8n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Turns an n8n HTTP reply into a short sentence the owner can read. */
object N8nResultWords {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun describe(status: Int, body: String, workflowName: String): String {
        val trimmed = body.trim()
        val fromJson = jsonBits(trimmed)
        val name = workflowName.ifBlank { "the workflow" }
        return when {
            status in 200..299 && trimmed.isEmpty() ->
                "n8n accepted $name and sent no extra detail."
            status in 200..299 ->
                "n8n ran $name. ${fromJson ?: shorten(trimmed)}"
            status == 401 || status == 403 ->
                "n8n rejected the API key. ${fromJson ?: "Check the key saved on this phone."}"
            status == 404 ->
                "n8n could not find that webhook. Is $name switched on, and is the path still the same?"
            status == 0 ->
                fromJson ?: "Could not reach n8n."
            else ->
                "n8n answered with $status. ${fromJson ?: shorten(trimmed).ifBlank { "No extra detail." }}"
        }
    }

    fun connection(status: Int, workflowCount: Int?, detail: String? = null): String {
        return when {
            status in 200..299 && workflowCount != null ->
                if (workflowCount == 1) {
                    "Reached n8n. Found 1 workflow."
                } else {
                    "Reached n8n. Found $workflowCount workflows."
                }
            status == 401 || status == 403 ->
                "Reached n8n, but the API key was rejected."
            status == 0 ->
                detail ?: "Could not reach the server. Is n8n running, and is the address right?"
            else ->
                detail ?: "n8n answered with $status."
        }
    }

    private fun jsonBits(body: String): String? {
        if (body.isEmpty() || (!body.startsWith("{") && !body.startsWith("["))) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        if (root is JsonObject) {
            val message = (root["message"] as? JsonPrimitive)?.contentOrNull
                ?: (root["error"] as? JsonPrimitive)?.contentOrNull
                ?: (root["hint"] as? JsonPrimitive)?.contentOrNull
            if (!message.isNullOrBlank()) return shorten(message)
        }
        return shorten(body)
    }

    private fun shorten(text: String, max: Int = 400): String {
        val compact = text.replace(Regex("\\s+"), " ").trim()
        if (compact.length <= max) return compact
        return compact.take(max).trimEnd() + "…"
    }
}
