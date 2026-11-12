package ai.droidcommand.agent

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class InvalidConversationId(id: String) : IllegalArgumentException(
    "Conversation id '$id' must match ${JsonFileConversationStore.ID_PATTERN.pattern} (no path separators or traversal)",
)

@Serializable
private data class ToolCallDto(val id: String, val name: String, val arguments: String)

/** The two tool fields are written only when set, so a conversation without tool calls is the same file as before. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
private data class MessageDto(
    val role: String,
    val content: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
private data class ConversationDto(
    @SerialName("system_prompt") val systemPrompt: String? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val messages: List<MessageDto>,
)

/**
 * A real, file-backed [ConversationStore]: each conversation is one JSON
 * file under [directory], surviving a process restart — the same
 * survives-a-restart guarantee [JsonFileMacroStore] already provides for
 * macros, applied to ROADMAP-126's conversation-memory gap instead.
 *
 * [conversationId] must match [ID_PATTERN]; the resolved file path is then
 * re-checked to stay inside [directory] before any read/write/delete — the
 * identical fail-closed, normalize-then-`startsWith` pattern
 * [JsonFileMacroStore] and `core-build.WorkspacePathValidator` both use, so
 * a conversation id can never resolve outside [directory].
 */
class JsonFileConversationStore(private val directory: Path) : ConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        Files.createDirectories(directory)
    }

    override fun save(conversationId: String, context: ConversationContext) {
        val file = fileFor(conversationId)
        val dto = ConversationDto(
            systemPrompt = context.systemPrompt,
            maxTokens = context.maxTokens,
            messages = context.messages.map { m ->
                MessageDto(m.role.name, m.content, m.toolCalls.map { ToolCallDto(it.id, it.name, it.argumentsJson) }, m.toolCallId)
            },
        )
        val bytes = json.encodeToString(ConversationDto.serializer(), dto).toByteArray()
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }

    override fun load(conversationId: String): ConversationContext? {
        val file = fileFor(conversationId)
        if (!Files.isRegularFile(file)) return null
        val dto = try {
            json.decodeFromString(ConversationDto.serializer(), Files.readString(file))
        } catch (e: SerializationException) {
            throw IOException("Conversation file for '$conversationId' is not valid JSON", e)
        }
        val context = ConversationContext(dto.systemPrompt, dto.maxTokens)
        for (message in dto.messages) {
            val role = try {
                Role.valueOf(message.role)
            } catch (e: IllegalArgumentException) {
                throw IOException("Conversation file for '$conversationId' has an unknown role '${message.role}'", e)
            }
            context.append(Message(role, message.content, message.toolCalls.map { ToolCallRequest(it.id, it.name, it.arguments) }, message.toolCallId))
        }
        return context
    }

    override fun list(): List<String> {
        if (!Files.isDirectory(directory)) return emptyList()
        Files.newDirectoryStream(directory, "*$EXTENSION").use { stream ->
            return stream.map { it.fileName.toString().removeSuffix(EXTENSION) }.sorted()
        }
    }

    override fun delete(conversationId: String): Boolean = Files.deleteIfExists(fileFor(conversationId))

    private fun fileFor(conversationId: String): Path {
        if (!ID_PATTERN.matches(conversationId)) throw InvalidConversationId(conversationId)
        val normalizedRoot = directory.normalize()
        val candidate = normalizedRoot.resolve("$conversationId$EXTENSION").normalize()
        if (!candidate.startsWith(normalizedRoot)) throw InvalidConversationId(conversationId)
        return candidate
    }

    companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
        private const val EXTENSION = ".conversation.json"
    }
}
