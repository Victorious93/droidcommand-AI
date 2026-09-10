package ai.droidcommand.agent

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Parses [JsonFileConversationStore]'s exact wire shape (`ConversationDto`/
 * `MessageDto`, widened to `internal` for this reuse rather than
 * duplicated — a duplicate declaration would risk silently drifting out
 * of sync with what that store actually writes). [canParse] is a real
 * decode attempt, not a guess.
 */
class NativeJsonImportParser : ConversationImportParser {
    override val format = ConversationImportFormat.NATIVE_JSON

    private val json = Json { ignoreUnknownKeys = true }

    override fun canParse(source: ImportSource): Boolean = tryDecode(source.content) != null

    override fun parse(source: ImportSource): ParseOutcome {
        val dto = tryDecode(source.content) ?: return ParseOutcome.Failed("not valid native-format conversation JSON")
        return ParseOutcome.Parsed(dto.messages.map { RawMessage(it.role, it.content) })
    }

    private fun tryDecode(content: String): ConversationDto? =
        try {
            json.decodeFromString(ConversationDto.serializer(), content)
        } catch (e: SerializationException) {
            null
        }
}

/**
 * Parses a simple, self-defined plain-text transcript: a line matching
 * `Label: content` starts a new message (the label becomes
 * [RawMessage.rawRole]); a non-matching line continues the current
 * message's content, newline-joined. Content appearing before the first
 * role marker is a real [ParseOutcome.Failed] — never silently dropped,
 * matching this codebase's "never silently discard content" ethos
 * elsewhere (e.g. [ConversationContext.append]'s own eviction rules).
 *
 * [canParse] requires at least one line whose label is a *recognized*
 * role (via [normalizeRole]) — real confidence, not "any colon-shaped
 * line." [parse] itself stays purely structural (any `label: text` line
 * starts a message), deliberately leaving judgment of unfamiliar labels
 * to the shared Normalization/Validation stage in
 * [ConversationImportPipeline] rather than duplicating that judgment
 * here — a stray `Note: ...` line mixed into a real transcript surfaces
 * as [ConversationImportError.UnrecognizedRole], not a silent misparse.
 */
class PlainTextTranscriptImportParser : ConversationImportParser {
    override val format = ConversationImportFormat.PLAIN_TEXT_TRANSCRIPT

    private val roleLine = Regex("""^\s*([A-Za-z]+)\s*:\s*(.*)$""")

    override fun canParse(source: ImportSource): Boolean =
        source.content.lineSequence().any { line ->
            roleLine.matchEntire(line)?.let { normalizeRole(it.groupValues[1]) != null } ?: false
        }

    override fun parse(source: ImportSource): ParseOutcome {
        val messages = mutableListOf<RawMessage>()
        var currentRole: String? = null
        val currentContent = StringBuilder()

        fun flush() {
            currentRole?.let { messages += RawMessage(it, currentContent.toString().trim()) }
            currentContent.clear()
        }

        for (line in source.content.lines()) {
            val match = roleLine.matchEntire(line)
            when {
                match != null -> {
                    flush()
                    currentRole = match.groupValues[1]
                    currentContent.append(match.groupValues[2])
                }
                currentRole != null -> {
                    if (currentContent.isNotEmpty()) currentContent.append('\n')
                    currentContent.append(line)
                }
                line.isNotBlank() -> return ParseOutcome.Failed("content precedes the first recognized role marker")
            }
        }
        flush()

        return ParseOutcome.Parsed(messages)
    }
}
