package ai.droidcommand.agent

import java.time.Instant

/**
 * Raw input to the import pipeline (the "File" stage of CAP-006/P0.6's
 * architecture). `core-agent` has no filesystem/Android dependency, so
 * reading actual bytes is the caller's job — this carries the already-
 * decoded text plus [fileNameHint], used only for reporting (e.g. in
 * [ConversationImportResult.UnrecognizedFormat]), never for format
 * detection itself (every [ConversationImportParser] decides that from
 * [content] alone).
 */
data class ImportSource(val fileNameHint: String, val content: String)

/**
 * The formats this slice can honestly parse. Vendor chat export formats
 * (ChatGPT, Claude.ai, Slack, etc.) are deliberately not included — their
 * real schemas are neither verifiable nor stable from inside this
 * session, and parsing against a guessed schema would silently produce
 * wrong results on real files. Adding one later is a new
 * [ConversationImportParser] registration, not a redesign of this enum
 * or the pipeline.
 */
enum class ConversationImportFormat { NATIVE_JSON, PLAIN_TEXT_TRANSCRIPT }

/** A parser's raw output for one message, before role normalization. */
data class RawMessage(val rawRole: String, val content: String)

/** The Parser stage's outcome: either the raw messages it found, or why it couldn't parse the source at all. */
sealed class ParseOutcome {
    data class Parsed(val messages: List<RawMessage>) : ParseOutcome()

    data class Failed(val reason: String) : ParseOutcome()
}

/**
 * One format's Format Detection + Parser stages. [canParse] self-declares
 * whether this parser recognizes [ImportSource.content];
 * [ConversationImportPipeline] tries each registered parser in order and
 * uses the first that claims the source — the same "provider
 * self-declares, caller doesn't guess" shape [ContextProvider] already
 * uses.
 */
interface ConversationImportParser {
    val format: ConversationImportFormat

    fun canParse(source: ImportSource): Boolean

    fun parse(source: ImportSource): ParseOutcome
}

/**
 * The Normalization stage: maps a free-text role label onto the
 * canonical [Role]. A first, reasonable alias set — not exhaustive —
 * matching this codebase's "honest heuristic" precedent
 * ([estimateTokens], `DefaultTokenBudgetManager`'s `ComplexityThresholds`).
 * Case-insensitive; anything unrecognized is `null`, surfaced as a real
 * [ConversationImportError.UnrecognizedRole] rather than guessed at.
 */
fun normalizeRole(raw: String): Role? = when (raw.trim().lowercase()) {
    "user", "human", "you" -> Role.USER
    "assistant", "ai", "bot" -> Role.ASSISTANT
    "system" -> Role.SYSTEM
    "tool" -> Role.TOOL
    else -> null
}

/**
 * A problem found during the Normalization/Validation stages. Every
 * problem in a source is collected in one pass — the same "report every
 * structural error together, not one at a time" discipline
 * [TaskGraph.from] already established — rather than failing on the
 * first bad message.
 */
sealed class ConversationImportError {
    data object EmptyConversation : ConversationImportError()

    data class UnrecognizedRole(val messageIndex: Int, val raw: String) : ConversationImportError()

    data class BlankContent(val messageIndex: Int) : ConversationImportError()
}

/**
 * The "Conversation Records" stage's output: a validated, normalized
 * conversation. [toConversationContext] is the bridge into the already-
 * real [ConversationContext]/[ConversationStore] machinery — this
 * pipeline does not invent a second persistence path.
 */
data class ImportedConversation(
    val messages: List<Message>,
    val sourceFormat: ConversationImportFormat,
    val sourceFileNameHint: String,
    val importedAt: Instant = Instant.now(),
) {
    fun toConversationContext(systemPrompt: String? = null, maxTokens: Int? = null): ConversationContext =
        ConversationContext(systemPrompt, maxTokens).also { context -> messages.forEach(context::append) }
}

/** The pipeline's outcome — a typed result instead of throwing, matching this codebase's usual discipline. */
sealed class ConversationImportResult {
    data class Success(val conversation: ImportedConversation) : ConversationImportResult()

    data class UnrecognizedFormat(val fileNameHint: String) : ConversationImportResult()

    data class ParseFailed(val format: ConversationImportFormat, val reason: String) : ConversationImportResult()

    data class ValidationFailed(val format: ConversationImportFormat, val errors: List<ConversationImportError>) : ConversationImportResult()
}

/**
 * Runs CAP-006/P0.6's Format Detection → Parser → Normalization →
 * Validation → Conversation Records stages in one pass. **The Analyzer
 * and Storage stages are deliberately not run here:** Analyzer would feed
 * a Persona system (P0.5) that is still MISSING — nothing real exists to
 * analyze into yet; Storage is a caller decision, matching every prior
 * aggregation-layer slice's own precedent
 * (`KnowledgeExtractionService`/`AnalyzedObjectiveRunner`) — a caller
 * takes [ConversationImportResult.Success.conversation]'s
 * [ImportedConversation.toConversationContext] and calls
 * [ConversationStore.save] themselves.
 */
class ConversationImportPipeline(private val parsers: List<ConversationImportParser>) {
    fun import(source: ImportSource): ConversationImportResult {
        val parser = parsers.firstOrNull { it.canParse(source) }
            ?: return ConversationImportResult.UnrecognizedFormat(source.fileNameHint)

        val rawMessages = when (val outcome = parser.parse(source)) {
            is ParseOutcome.Failed -> return ConversationImportResult.ParseFailed(parser.format, outcome.reason)
            is ParseOutcome.Parsed -> outcome.messages
        }

        val errors = mutableListOf<ConversationImportError>()
        if (rawMessages.isEmpty()) errors += ConversationImportError.EmptyConversation

        val messages = mutableListOf<Message>()
        rawMessages.forEachIndexed { index, raw ->
            val role = normalizeRole(raw.rawRole)
            when {
                role == null -> errors += ConversationImportError.UnrecognizedRole(index, raw.rawRole)
                raw.content.isBlank() -> errors += ConversationImportError.BlankContent(index)
                else -> messages += Message(role, raw.content)
            }
        }

        if (errors.isNotEmpty()) return ConversationImportResult.ValidationFailed(parser.format, errors)

        return ConversationImportResult.Success(
            ImportedConversation(messages, parser.format, source.fileNameHint),
        )
    }

    companion object {
        /** The two real, verified-schema formats this slice supports — see [ConversationImportFormat]'s own doc. */
        fun default(): ConversationImportPipeline =
            ConversationImportPipeline(listOf(NativeJsonImportParser(), PlainTextTranscriptImportParser()))
    }
}
