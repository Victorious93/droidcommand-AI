package ai.droidcommand.llm.google

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for Google's Gemini `generateContent` / `streamGenerateContent`
 * API (`POST /v1beta/models/{model}:generateContent`), kept separate from
 * [ai.droidcommand.llm.LlmRequest]/[ai.droidcommand.llm.LlmResponse] so the
 * provider-independent contract in core-llm never depends on one vendor's JSON
 * shape — the same separation `core-llm-anthropic` and `core-llm-openai`
 * already establish.
 */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiSystemInstruction? = null,
    val tools: List<GeminiTool>? = null,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart>,
)

@Serializable
data class GeminiPart(
    val text: String? = null,
    val functionCall: GeminiFunctionCall? = null,
)

@Serializable
data class GeminiSystemInstruction(val parts: List<GeminiPart>)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    @SerialName("maxOutputTokens") val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val responseSchema: JsonObject? = null,
)

@Serializable
data class GeminiTool(val functionDeclarations: List<GeminiFunctionDeclaration>)

/**
 * [parameters] is a permissive `{"type":"object"}` for every tool, for the
 * same reason as the other providers: [ai.droidcommand.agent.ToolSpec] models
 * no parameter shape yet.
 */
@Serializable
data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val error: GeminiError? = null,
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null,
    @SerialName("finishReason") val finishReason: String? = null,
)

@Serializable
data class GeminiFunctionCall(
    val name: String,
    val args: JsonObject? = null,
)

/** Top-level error object returned by Gemini on 4xx/5xx responses. */
@Serializable
data class GeminiError(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

/** Wrapper for error responses: `{"error": {...}}`. */
@Serializable
data class GeminiErrorResponse(val error: GeminiError? = null)
