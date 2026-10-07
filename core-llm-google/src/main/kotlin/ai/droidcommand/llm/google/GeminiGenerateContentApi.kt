package ai.droidcommand.llm.google

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire types for the Gemini API's `generateContent` /
 * `streamGenerateContent` shape — deliberately not OpenAI-compatible
 * (`contents`/`parts` instead of `messages`, `model` instead of
 * `assistant`, `systemInstruction` and `generationConfig` as separate
 * objects). Kept separate from core-llm's request/response types so the
 * provider-independent contract never depends on this vendor's JSON.
 */
@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null,
    val tools: List<GeminiTool>? = null,
)

@Serializable
data class GeminiContent(val role: String? = null, val parts: List<GeminiPart> = emptyList())

/** A part holds exactly one of [text] / [functionCall]; [thought] marks reasoning text that must not be shown as the answer. */
@Serializable
data class GeminiPart(
    val text: String? = null,
    val functionCall: GeminiFunctionCall? = null,
    val thought: Boolean? = null,
)

@Serializable
data class GeminiFunctionCall(val name: String, val args: JsonObject? = null)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    /** Standard JSON Schema, via Gemini's `responseJsonSchema` field. */
    val responseJsonSchema: JsonElement? = null,
)

@Serializable
data class GeminiTool(val functionDeclarations: List<GeminiFunctionDeclaration>)

/** [parameters] is omitted: core-agent's ToolSpec models no parameter shape, and Gemini rejects an empty OBJECT schema. */
@Serializable
data class GeminiFunctionDeclaration(val name: String, val description: String)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null,
    val error: GeminiError? = null,
)

@Serializable
data class GeminiCandidate(val content: GeminiContent? = null, val finishReason: String? = null)

@Serializable
data class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
data class GeminiError(val code: Int? = null, val message: String? = null, val status: String? = null)
