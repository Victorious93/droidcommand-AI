package ai.droidcommand.security

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Contract v1 for inbound code assist from VictorFiles (Victorious93/File-Manager
 * `docs/PHASE17.md` §1). Field names and extras are that document's, not invented.
 *
 * JVM-testable: request parse, 60 000-char cap, caller pin, result JSON. The
 * Android Activity is a thin shell around this.
 */
object CodeAssist {
    const val ACTION = "ai.droidcommand.action.CODE_ASSIST"
    const val EXTRA_REQUEST_JSON = "ai.droidcommand.extra.REQUEST_JSON"
    const val EXTRA_RESULT_JSON = "ai.droidcommand.extra.RESULT_JSON"

    /** PHASE17 binder cap: `code` is at most 60 000 characters. */
    const val MAX_CODE_CHARS = 60_000

    const val CONTRACT_VERSION = 1

    const val TASK_EXPLAIN = "explain"
    const val TASK_SUGGEST_EDIT = "suggest_edit"

    const val FILE_MANAGER_PACKAGE = "ai.victorious.filemanager"
    const val FILE_MANAGER_DEBUG_PACKAGE = "ai.victorious.filemanager.debug"

    /**
     * Consent-screen snippet length. Preview the exact text [buildPrompt] will
     * send (not raw [CodeAssistRequest.code] alone), so Allow cannot approve a
     * different prompt than the user saw.
     */
    const val PREVIEW_CHARS = 1_200

    fun callerVerifier(): NodeSignatureVerifier = NodeSignatureVerifier(
        identities = KnownNodeSigningCatalog.codeAssistCallerIdentities(),
        missingExpectedPolicy = MissingExpectedCertPolicy.REFUSE,
    )

    /**
     * Fail-closed caller check. [packageName] comes from
     * `getCallingPackage()` / `getCallingActivity()` on a started-for-result
     * Activity. Missing identity, unknown package, or cert mismatch all refuse.
     */
    fun verifyCaller(
        packageName: String?,
        observedFingerprints: Collection<String>,
    ): NodeSignatureDecision {
        if (packageName.isNullOrBlank()) {
            return NodeSignatureDecision.Refused(
                packageName = packageName.orEmpty(),
                kind = NodeSignatureRefusal.EXPECTED_CERT_MISSING,
                message = "Refusing CODE_ASSIST: caller identity is missing " +
                    "(activity was not started for result, or getCallingPackage() " +
                    "and getCallingActivity() were both empty).",
            )
        }
        return callerVerifier().verify(packageName, observedFingerprints)
    }

    fun parseRequest(raw: String?): CodeAssistParse {
        if (raw.isNullOrBlank()) {
            return CodeAssistParse.Invalid("Missing $EXTRA_REQUEST_JSON.")
        }
        val root = try {
            JSON.parseToJsonElement(raw)
        } catch (e: Exception) {
            return CodeAssistParse.Invalid("REQUEST_JSON is not valid JSON.")
        }
        val obj = root as? JsonObject
            ?: return CodeAssistParse.Invalid("REQUEST_JSON must be a JSON object.")

        val version = versionOf(obj)
            ?: return CodeAssistParse.Invalid("REQUEST_JSON field 'v' must be $CONTRACT_VERSION.")
        if (version != CONTRACT_VERSION) {
            return CodeAssistParse.Invalid("Unsupported CODE_ASSIST contract v=$version.")
        }

        val task = when (obj.string("task")) {
            TASK_EXPLAIN -> CodeAssistTask.EXPLAIN
            TASK_SUGGEST_EDIT -> CodeAssistTask.SUGGEST_EDIT
            else -> return CodeAssistParse.Invalid(
                "REQUEST_JSON field 'task' must be '$TASK_EXPLAIN' or '$TASK_SUGGEST_EDIT'.",
            )
        }
        val code = obj.string("code")
            ?: return CodeAssistParse.Invalid("REQUEST_JSON field 'code' is required.")
        if (code.isEmpty()) {
            return CodeAssistParse.Invalid("REQUEST_JSON field 'code' is empty.")
        }
        if (code.length > MAX_CODE_CHARS) {
            return CodeAssistParse.Invalid(
                "REQUEST_JSON field 'code' exceeds $MAX_CODE_CHARS characters " +
                    "(got ${code.length}).",
            )
        }

        return CodeAssistParse.Valid(
            CodeAssistRequest(
                task = task,
                fileName = obj.string("fileName").orEmpty(),
                language = obj.string("language").orEmpty(),
                code = code,
                instruction = obj.string("instruction").orEmpty(),
                prompt = obj.string("prompt").orEmpty(),
                truncated = obj.boolean("truncated") ?: false,
            ),
        )
    }

    /** Prefer File-Manager's ready-made [CodeAssistRequest.prompt] when present. */
    fun buildPrompt(request: CodeAssistRequest): String {
        val ready = request.prompt.trim()
        if (ready.isNotEmpty()) return ready
        val where = buildString {
            if (request.language.isNotBlank()) append(request.language).append(" ")
            append("code")
            if (request.fileName.isNotBlank()) append(" from ").append(request.fileName)
        }
        return when (request.task) {
            CodeAssistTask.EXPLAIN -> buildString {
                append("Explain the following ").append(where).append(".\n")
                if (request.instruction.isNotBlank()) {
                    append(request.instruction.trim()).append("\n")
                }
                append("\n").append(request.code)
            }
            CodeAssistTask.SUGGEST_EDIT -> buildString {
                append("Suggest an edit to the following ").append(where)
                append(". Return the full revised file.\n")
                if (request.instruction.isNotBlank()) {
                    append("Instruction: ").append(request.instruction.trim()).append("\n")
                }
                append("\n").append(request.code)
            }
        }
    }

    fun successJson(task: CodeAssistTask, modelText: String): String {
        val explanation = modelText
        val fields = linkedMapOf(
            "v" to JsonPrimitive(CONTRACT_VERSION),
            "ok" to JsonPrimitive(true),
            "explanation" to JsonPrimitive(explanation),
        )
        if (task == CodeAssistTask.SUGGEST_EDIT) {
            fields["revisedCode"] = JsonPrimitive(revisedCodeFrom(modelText))
        }
        return JSON.encodeToString(JsonObject(fields))
    }

    fun errorJson(message: String): String = JSON.encodeToString(
        JsonObject(
            mapOf(
                "v" to JsonPrimitive(CONTRACT_VERSION),
                "ok" to JsonPrimitive(false),
                "error" to JsonPrimitive(message),
            ),
        ),
    )

    /**
     * PHASE17: if `revisedCode` is missing, VictorFiles takes the largest
     * fenced code block in `explanation`. We fill `revisedCode` ourselves:
     * largest fenced block when present, otherwise the full model text.
     */
    fun revisedCodeFrom(modelText: String): String {
        val blocks = FENCE.findAll(modelText).map { it.groupValues[1].trimEnd() }.toList()
        return blocks.maxByOrNull { it.length } ?: modelText
    }

    fun preview(code: String, maxChars: Int = PREVIEW_CHARS): String {
        if (code.length <= maxChars) return code
        return code.take(maxChars) + "\n… (${code.length - maxChars} more characters)"
    }

    /**
     * JVM-testable consent payload: [promptToSend] is exactly what
     * [buildPrompt] returns (and what the Activity must send after Allow).
     * [promptPreview] is that same text truncated for the Compose screen.
     * [instruction] is surfaced separately so a ready-made [CodeAssistRequest.prompt]
     * cannot hide the caller's instruction.
     */
    fun consentContent(
        request: CodeAssistRequest,
        previewMaxChars: Int = PREVIEW_CHARS,
    ): CodeAssistConsentContent {
        val promptToSend = buildPrompt(request)
        return CodeAssistConsentContent(
            promptToSend = promptToSend,
            promptPreview = preview(promptToSend, previewMaxChars),
            instruction = request.instruction.trim(),
        )
    }

    private fun versionOf(obj: JsonObject): Int? {
        val value = obj["v"] ?: return CONTRACT_VERSION
        val primitive = value as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it }
        return primitive.contentOrNull?.toIntOrNull()
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return primitive.content
        return primitive.contentOrNull
    }

    private fun JsonObject.boolean(key: String): Boolean? {
        val value = this[key] ?: return null
        val primitive = value as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    private val JSON = Json { ignoreUnknownKeys = true }
    private val FENCE = Regex("```(?:[^\\n]*)\\n(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL))
}

enum class CodeAssistTask {
    EXPLAIN,
    SUGGEST_EDIT,
    ;

    fun wireName(): String = when (this) {
        EXPLAIN -> CodeAssist.TASK_EXPLAIN
        SUGGEST_EDIT -> CodeAssist.TASK_SUGGEST_EDIT
    }
}

data class CodeAssistRequest(
    val task: CodeAssistTask,
    val fileName: String,
    val language: String,
    val code: String,
    val instruction: String,
    val prompt: String,
    val truncated: Boolean,
)

sealed class CodeAssistParse {
    data class Valid(val request: CodeAssistRequest) : CodeAssistParse()

    data class Invalid(val reason: String) : CodeAssistParse()
}

/**
 * What the CODE_ASSIST consent screen must show, and the exact prompt the
 * Activity sends after Allow. [promptToSend] == [CodeAssist.buildPrompt].
 */
data class CodeAssistConsentContent(
    val promptToSend: String,
    val promptPreview: String,
    val instruction: String,
)
