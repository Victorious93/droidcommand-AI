package ai.droidcommand.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A format [ConversationParser] can recognize and parse (CAP-006, P0.6).
 * Deliberately limited to two generic, vendor-neutral shapes this codebase
 * can define, parse, and verify entirely on its own terms — see
 * [ConversationImport.kt][ConversationImporter]'s own doc comment for why
 * vendor-specific export formats (ChatGPT/Claude.ai/etc.) are not attempted
 * here.
 */
enum class ConversationFormat { GENERIC_JSON, PLAIN_TEXT_TRANSCRIPT }

/** Thrown by [ConversationParser.parse] when content [ConversationParser.canParse] already accepted turns out malformed on the real parse pass. */
class ConversationParseException(message: String) : Exception(message)

/**
 * Format detection and parsing folded into one interface — a format is
 * always recognized and parsed by the same logic, so a separate "detector"
 * type would be a second, competing concept for the same responsibility
 * (the same reasoning [ContextProvider] already applies by staying one
 * `fun interface` rather than splitting "has content" from "provide it").
 */
interface ConversationParser {
    val format: ConversationFormat

    /** A cheap, safe check — never throws, even on completely malformed [content]. */
    fun canParse(fileName: String, content: String): Boolean

    /** Throws [ConversationParseException] if [content] turns out malformed despite [canParse] accepting it. */
    fun parse(content: String): List<Message>
}

@Serializable
private data class ImportMessageDto(val role: String, val content: String)

/**
 * The generic JSON message-array format: `[{"role": "...", "content":
 * "..."}, ...]` — the same shape `JsonFileConversationStore`'s own
 * `MessageDto` already round-trips, so this format is this codebase's own
 * export shape read back in, not a guess at anyone else's schema.
 */
class GenericJsonConversationParser : ConversationParser {
    override val format = ConversationFormat.GENERIC_JSON

    private val json = Json { ignoreUnknownKeys = true }

    override fun canParse(fileName: String, content: String): Boolean =
        try {
            json.decodeFromString(ListSerializer(ImportMessageDto.serializer()), content)
            true
        } catch (e: SerializationException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }

    override fun parse(content: String): List<Message> {
        val dtos = try {
            json.decodeFromString(ListSerializer(ImportMessageDto.serializer()), content)
        } catch (e: SerializationException) {
            throw ConversationParseException("malformed JSON message array: ${e.message}")
        }
        return dtos.map { dto ->
            val role = try {
                Role.valueOf(dto.role.uppercase())
            } catch (e: IllegalArgumentException) {
                throw ConversationParseException("unknown role '${dto.role}'")
            }
            Message(role, dto.content)
        }
    }
}

/**
 * A plain-text `Role: content` transcript — a common, vendor-neutral
 * convention rather than any one tool's proprietary export. Each line
 * starting with a recognized role name followed by `:` starts a new
 * message; every following line with no such prefix is appended (as a new
 * line) to the current message's content, so a multi-line reply stays one
 * [Message].
 */
class PlainTextTranscriptParser : ConversationParser {
    override val format = ConversationFormat.PLAIN_TEXT_TRANSCRIPT

    override fun canParse(fileName: String, content: String): Boolean = content.lineSequence().any { rolePrefix(it) != null }

    override fun parse(content: String): List<Message> {
        val messages = mutableListOf<Message>()
        var currentRole: Role? = null
        val currentContent = StringBuilder()

        fun flush() {
            currentRole?.let { messages += Message(it, currentContent.toString().trim()) }
            currentContent.clear()
        }

        for (line in content.lineSequence()) {
            val prefix = rolePrefix(line)
            if (prefix != null) {
                flush()
                currentRole = prefix.first
                currentContent.append(prefix.second)
            } else if (currentRole != null) {
                if (currentContent.isNotEmpty()) currentContent.append('\n')
                currentContent.append(line)
            }
        }
        flush()

        if (messages.isEmpty()) throw ConversationParseException("no recognizable 'Role: content' lines found")
        return messages
    }

    private fun rolePrefix(line: String): Pair<Role, String>? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val role = try {
            Role.valueOf(line.substring(0, colon).trim().uppercase())
        } catch (e: IllegalArgumentException) {
            return null
        }
        return role to line.substring(colon + 1).trim()
    }
}

/**
 * Real, structural stats about an imported conversation (CAP-006's
 * "Analyzer" pipeline step) — deliberately non-semantic. Tone/vocabulary/
 * style analysis is CAP-005 (Persona)'s own job, and needs an LLM call this
 * dependency-free `core-agent` module correctly has none of, the same
 * "not attempted here, named future `core-llm` work" reasoning
 * [TokenBudgetManager.selectBudget]'s own doc comment already applies to
 * `selectBudget`'s complexity heuristic.
 */
data class ImportedConversationSummary(
    val messageCount: Int,
    val roleCounts: Map<Role, Int>,
    val estimatedTokens: Int,
)

/** The result of [ConversationImporter.import]. */
sealed class ConversationImportResult {
    data class Success(
        val conversationId: String,
        val context: ConversationContext,
        val summary: ImportedConversationSummary,
    ) : ConversationImportResult()

    /** No registered [ConversationParser] claimed the content. */
    data object UnrecognizedFormat : ConversationImportResult()

    /** A parser claimed the content via [ConversationParser.canParse] but [ConversationParser.parse] failed on it. */
    data class ParseFailed(val format: ConversationFormat, val reason: String) : ConversationImportResult()

    /** Parsed successfully but failed structural validation — [store] was never touched. */
    data class ValidationFailed(val errors: List<String>) : ConversationImportResult()
}

/**
 * Imports an external conversation export into a real, storable
 * [ConversationContext] (CAP-006, P0.6): File → Format Detection → Parser →
 * Normalization → Validation → Conversation Records → Analyzer → Storage.
 *
 * **Deliberate scope limit, stated plainly:** only [GenericJsonConversationParser]
 * and [PlainTextTranscriptParser] are attempted — two generic, self-verifiable
 * formats, not any vendor's proprietary export schema (ChatGPT, Claude.ai,
 * etc.). Building a parser for one of those from memory alone, with no real
 * sample export in this environment to test against, would be exactly the
 * kind of unverified claim this codebase's honesty convention exists to
 * prevent — named future work once a real sample file is available, not
 * attempted here.
 */
interface ConversationImporter {
    fun import(conversationId: String, fileName: String, content: String): ConversationImportResult
}

/**
 * The real [ConversationImporter] implementation. [parsers] are tried in
 * order — the first whose [ConversationParser.canParse] accepts the content
 * wins, the same "first match, registration order" determinism this
 * codebase's other ordered-collection classes ([ModelRouter],
 * [LocalFirstOrdering][ai.droidcommand.llm.LocalFirstOrdering]) already
 * apply. [store] is caller-supplied (e.g. a real [JsonFileConversationStore])
 * — this class touches no existing file and forces no session to import
 * anything, matching [ContextManager]'s own caller-opt-in precedent.
 */
class DefaultConversationImporter(
    private val parsers: List<ConversationParser>,
    private val store: ConversationStore,
) : ConversationImporter {
    override fun import(conversationId: String, fileName: String, content: String): ConversationImportResult {
        val parser = parsers.firstOrNull { it.canParse(fileName, content) }
            ?: return ConversationImportResult.UnrecognizedFormat

        val messages = try {
            parser.parse(content)
        } catch (e: ConversationParseException) {
            return ConversationImportResult.ParseFailed(parser.format, e.message.orEmpty())
        }

        val errors = validate(messages)
        if (errors.isNotEmpty()) return ConversationImportResult.ValidationFailed(errors)

        val context = ConversationContext()
        messages.forEach { context.append(it) }
        store.save(conversationId, context)

        val summary = ImportedConversationSummary(
            messageCount = messages.size,
            roleCounts = messages.groupingBy { it.role }.eachCount(),
            estimatedTokens = context.estimatedTokens(),
        )
        return ConversationImportResult.Success(conversationId, context, summary)
    }

    private fun validate(messages: List<Message>): List<String> = buildList {
        if (messages.isEmpty()) add("conversation has no messages")
        messages.forEachIndexed { index, message ->
            if (message.content.isBlank()) add("message $index has blank content")
        }
    }
}
