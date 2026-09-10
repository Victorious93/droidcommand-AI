package ai.droidcommand.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationImportPipelineTest {
    private val pipeline = ConversationImportPipeline.default()

    @Test
    fun `native JSON with a valid file succeeds with correct role, content, systemPrompt and maxTokens`() {
        val json = """
            {"system_prompt":"be helpful","max_tokens":500,"messages":[{"role":"USER","content":"hi"},{"role":"ASSISTANT","content":"hello"}]}
        """.trimIndent()
        val result = pipeline.import(ImportSource("chat.json", json))

        val success = assertIs<ConversationImportResult.Success>(result)
        assertEquals(ConversationImportFormat.NATIVE_JSON, success.conversation.sourceFormat)
        assertEquals(listOf(Message(Role.USER, "hi"), Message(Role.ASSISTANT, "hello")), success.conversation.messages)

        val context = success.conversation.toConversationContext(systemPrompt = "be helpful", maxTokens = 500)
        assertEquals("be helpful", context.systemPrompt)
        assertEquals(500, context.maxTokens)
        assertEquals(2, context.messages.size)
    }

    @Test
    fun `native JSON with an unknown role fails validation with UnrecognizedRole`() {
        val json = """{"messages":[{"role":"NARRATOR","content":"once upon a time"}]}"""
        val result = pipeline.import(ImportSource("chat.json", json))

        val failed = assertIs<ConversationImportResult.ValidationFailed>(result)
        assertEquals(listOf(ConversationImportError.UnrecognizedRole(0, "NARRATOR")), failed.errors)
    }

    @Test
    fun `native JSON with an empty messages array fails validation with EmptyConversation`() {
        val json = """{"messages":[]}"""
        val result = pipeline.import(ImportSource("chat.json", json))

        val failed = assertIs<ConversationImportResult.ValidationFailed>(result)
        assertEquals(listOf(ConversationImportError.EmptyConversation), failed.errors)
    }

    @Test
    fun `native JSON with blank content fails validation with BlankContent`() {
        val json = """{"messages":[{"role":"USER","content":"   "}]}"""
        val result = pipeline.import(ImportSource("chat.json", json))

        val failed = assertIs<ConversationImportResult.ValidationFailed>(result)
        assertEquals(listOf(ConversationImportError.BlankContent(0)), failed.errors)
    }

    @Test
    fun `plain text transcript with a simple two-turn exchange succeeds`() {
        val transcript = "User: hi there\nAssistant: hello, how can I help?"
        val result = pipeline.import(ImportSource("chat.txt", transcript))

        val success = assertIs<ConversationImportResult.Success>(result)
        assertEquals(ConversationImportFormat.PLAIN_TEXT_TRANSCRIPT, success.conversation.sourceFormat)
        assertEquals(
            listOf(Message(Role.USER, "hi there"), Message(Role.ASSISTANT, "hello, how can I help?")),
            success.conversation.messages,
        )
    }

    @Test
    fun `plain text role aliases all normalize correctly`() {
        val transcript = "You: hi\nAI: hello\nHuman: how are you\nBot: fine"
        val result = pipeline.import(ImportSource("chat.txt", transcript))

        val success = assertIs<ConversationImportResult.Success>(result)
        assertEquals(
            listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT),
            success.conversation.messages.map { it.role },
        )
    }

    @Test
    fun `plain text multi-line content under one role is newline-joined`() {
        val transcript = "User: line one\nline two\nline three\nAssistant: ok"
        val result = pipeline.import(ImportSource("chat.txt", transcript))

        val success = assertIs<ConversationImportResult.Success>(result)
        assertEquals("line one\nline two\nline three", success.conversation.messages.first().content)
    }

    @Test
    fun `plain text content before the first role marker fails parsing`() {
        val transcript = "some preamble text\nUser: hi"
        val result = pipeline.import(ImportSource("chat.txt", transcript))

        assertIs<ConversationImportResult.ParseFailed>(result)
    }

    @Test
    fun `plain text an unrecognized label mixed among real roles fails validation at the right index`() {
        val transcript = "User: hi\nNote: this is an annotation\nAssistant: hello"
        val result = pipeline.import(ImportSource("chat.txt", transcript))

        val failed = assertIs<ConversationImportResult.ValidationFailed>(result)
        assertEquals(listOf(ConversationImportError.UnrecognizedRole(1, "Note")), failed.errors)
    }

    @Test
    fun `unrecognized content that is neither valid native JSON nor a recognized-role transcript is reported as such`() {
        val result = pipeline.import(ImportSource("mystery.dat", "just some random bytes, no structure at all"))

        val unrecognized = assertIs<ConversationImportResult.UnrecognizedFormat>(result)
        assertEquals("mystery.dat", unrecognized.fileNameHint)
    }

    @Test
    fun `a successful import round-trips through a real ConversationStore unchanged`() {
        val transcript = "User: hi\nAssistant: hello"
        val success = assertIs<ConversationImportResult.Success>(pipeline.import(ImportSource("chat.txt", transcript)))

        val store = InMemoryConversationStore()
        store.save("imported-1", success.conversation.toConversationContext())
        val loaded = store.load("imported-1")!!

        assertEquals(success.conversation.messages, loaded.messages)
    }

    @Test
    fun `import is deterministic across repeated calls, aside from importedAt`() {
        val json = """{"messages":[{"role":"USER","content":"hi"}]}"""
        val source = ImportSource("chat.json", json)

        val first = assertIs<ConversationImportResult.Success>(pipeline.import(source))
        val second = assertIs<ConversationImportResult.Success>(pipeline.import(source))

        assertEquals(first.conversation.messages, second.conversation.messages)
        assertEquals(first.conversation.sourceFormat, second.conversation.sourceFormat)
        assertEquals(first.conversation.sourceFileNameHint, second.conversation.sourceFileNameHint)
    }

    @Test
    fun `native JSON is preferred over the plain text parser when both are registered`() {
        val json = """{"messages":[{"role":"USER","content":"hi"}]}"""
        val result = pipeline.import(ImportSource("chat.json", json))

        val success = assertIs<ConversationImportResult.Success>(result)
        assertTrue(success.conversation.sourceFormat == ConversationImportFormat.NATIVE_JSON)
    }
}
