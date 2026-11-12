package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.agent.ToolArgumentCodec
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.llm.LlmError
import ai.droidcommand.llm.LlmResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Whether an on-device model and runtime can be offered tools, and the sentence to show when they cannot. */
data class LocalToolSupport(val supported: Boolean, val reason: String)

/**
 * Tool calling for on-device models, for the one convention this runtime can honour end to end: the
 * `<tool_call>{"name": …, "arguments": {…}}</tool_call>` format that Qwen 2.5 and Hermes-style models are trained on
 * and declare in their own chat template.
 *
 * Three independent parts, so no single one is trusted:
 * - [systemBlock] describes the offered tools to the model in the words those templates use.
 * - [grammar] is a GBNF grammar the backend samples under: the reply is either ordinary text or one to
 *   [MAX_CALLS] well-formed calls naming an offered tool. A backend without grammar support is not offered tools.
 * - [parse] checks the finished text again in Kotlin (well-formed JSON, an offered tool name, object arguments)
 *   before anything can be executed. It does not rely on the grammar having been applied.
 *
 * Bounded: at most [MAX_CALLS] calls per reply, and the caller bounds the number of rounds. Ordinary replies cannot
 * begin with `<` while tools are offered (that character is how a call starts); nothing else about them changes.
 */
object LocalToolProtocol {
    const val MAX_CALLS = 4
    const val OPEN = "<tool_call>"
    const val CLOSE = "</tool_call>"

    const val NO_GRAMMAR = "This on-device runtime cannot hold a reply to the tool-call format, so tools were not offered and this reply was written without them."
    const val NO_TEMPLATE_SUPPORT =
        "This on-device model's chat template does not define tool calls ($OPEN), so tools were not offered and this reply was written without them. " +
            "A model trained for it (for example Qwen 2.5 Instruct or a Hermes model) can use them."
    const val UNREADABLE = "This on-device model's file could not be read to check for tool-call support, so tools were not offered and this reply was written without them."

    private val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,64}")

    /** True when a chat template (the GGUF's `tokenizer.chat_template`) renders tool calls in this format. */
    fun templateDeclaresToolCalls(template: String): Boolean = template.contains(OPEN) && template.contains("tools")

    fun support(metadata: GgufMetadata?, backend: InferenceBackend): LocalToolSupport = when {
        !backend.supportsGrammar -> LocalToolSupport(false, NO_GRAMMAR)
        metadata == null -> LocalToolSupport(false, UNREADABLE)
        !metadata.chatTemplateDeclaresToolCalls -> LocalToolSupport(false, NO_TEMPLATE_SUPPORT)
        else -> LocalToolSupport(true, "This on-device model can call tools.")
    }

    /** Tool names that cannot be written into the grammar; a request offering one of these is refused, not trimmed. */
    fun unusableNames(tools: List<ToolSpec>): List<String> = tools.map { it.name }.filterNot(TOOL_NAME::matches)

    /** System text that tells the model which tools exist and how to call one. */
    fun systemBlock(tools: List<ToolSpec>): String {
        val signatures = tools.joinToString("\n") { tool ->
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put(
                    "function",
                    buildJsonObject {
                        put("name", JsonPrimitive(tool.name))
                        put("description", JsonPrimitive(tool.description))
                        put("parameters", parameters(tool))
                    },
                )
            }.toString()
        }
        return "# Tools\n\nYou may call one or more functions to assist with the user query.\n\n" +
            "You are provided with function signatures within <tools></tools> XML tags:\n<tools>\n$signatures\n</tools>\n\n" +
            "For each function call, return a json object with function name and arguments within $OPEN$CLOSE XML tags:\n" +
            "$OPEN\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n$CLOSE"
    }

    private fun parameters(tool: ToolSpec): JsonObject {
        val schema = tool.parameterSchema ?: return buildJsonObject { put("type", JsonPrimitive("object")) }
        return buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    for (parameter in schema.parameters) {
                        put(
                            parameter.name,
                            buildJsonObject {
                                put("type", JsonPrimitive(parameter.type.name.lowercase()))
                                if (parameter.description.isNotBlank()) put("description", JsonPrimitive(parameter.description))
                            },
                        )
                    }
                },
            )
            put("required", JsonArray(schema.parameters.filter { it.required }.map { JsonPrimitive(it.name) }))
        }
    }

    /**
     * GBNF (llama.cpp grammar) for a reply while [tools] are offered. Whitespace and number lengths are bounded so a
     * model cannot spend its whole reply budget inside one token class.
     */
    fun grammar(tools: List<ToolSpec>): String {
        val names = tools.joinToString(" | ") { "\"\\\"${it.name}\\\"\"" }
        return """
            root ::= ws ( call{1,$MAX_CALLS} | text )?
            call ::= "$OPEN" ws "{" ws "\"name\"" ws ":" ws name ws "," ws "\"arguments\"" ws ":" ws object ws "}" ws "$CLOSE" ws
            name ::= $names
            text ::= [^< \t\n\r] [^\x00]*
            value ::= object | array | string | number | "true" | "false" | "null"
            object ::= "{" ws ( string ws ":" ws value ws ( "," ws string ws ":" ws value ws )* )? "}"
            array ::= "[" ws ( value ws ( "," ws value ws )* )? "]"
            string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ( ["\\bfnrt/] | "u" [0-9a-fA-F]{4} ) )* "\""
            number ::= "-"? ( "0" | [1-9] [0-9]{0,15} ) ( "." [0-9]{1,16} )? ( [eE] [-+]? [0-9]{1,4} )?
            ws ::= [ \t\n\r]{0,8}
            """.trimIndent() + "\n"
    }

    /**
     * The conversation as plain role/content turns for a backend that renders only those. An assistant turn that
     * called tools is written back as the `<tool_call>` blocks it produced, and its results as one user turn of
     * `<tool_response>` blocks, which is how these models' own templates present them.
     */
    fun render(messages: List<Message>): List<Message> {
        val out = mutableListOf<Message>()
        val responses = mutableListOf<String>()

        fun flush() {
            if (responses.isEmpty()) return
            out += Message(Role.USER, responses.joinToString("\n") { "<tool_response>\n$it\n</tool_response>" })
            responses.clear()
        }
        for (message in messages) {
            if (message.role == Role.TOOL && message.toolCallId != null) {
                responses += message.content
                continue
            }
            flush()
            out += if (message.toolCalls.isEmpty()) {
                Message(message.role, message.content)
            } else {
                val calls = message.toolCalls.joinToString("\n") { "$OPEN\n{\"name\": ${JsonPrimitive(it.name)}, \"arguments\": ${it.argumentsJson}}\n$CLOSE" }
                Message(Role.ASSISTANT, listOf(message.content, calls).filter { it.isNotBlank() }.joinToString("\n"))
            }
        }
        flush()
        return out
    }

    /** Whether [soFar] (the start of a reply) can still turn out to be a tool call. Used to hold text back until it is known. */
    fun mayBeCall(soFar: String): Boolean {
        val trimmed = soFar.trimStart()
        return trimmed.length < OPEN.length && OPEN.startsWith(trimmed) || trimmed.startsWith(OPEN)
    }

    /**
     * The finished reply as [LlmResponse.Text], or as the tool calls it consists of, or as an error when it starts
     * like a call but is not a complete, valid one. An error means nothing may be executed.
     */
    fun parse(output: String, tools: List<ToolSpec>): LlmResponse {
        var rest = output.trim()
        if (!rest.startsWith(OPEN)) return LlmResponse.Text(output)
        val offered = tools.map { it.name }.toSet()
        val calls = mutableListOf<LlmResponse.ToolCall>()
        while (rest.isNotEmpty()) {
            if (!rest.startsWith(OPEN)) return invalid("text followed a tool call")
            val end = rest.indexOf(CLOSE)
            if (end < 0) return invalid("a tool call was cut off before it ended (the reply ran out of room)")
            val body = rest.substring(OPEN.length, end).trim()
            rest = rest.substring(end + CLOSE.length).trim()
            val parsed = ToolArgumentCodec.parseStrictObject(body) ?: return invalid("a tool call was not a JSON object")
            val name = (parsed["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return invalid("a tool call had no name")
            if (name !in offered) return invalid("it named a tool that was not offered ('${name.take(64)}')")
            val arguments = parsed["arguments"] as? JsonObject ?: return invalid("the arguments for '$name' were not a JSON object")
            if (calls.size == MAX_CALLS) return invalid("it asked for more than $MAX_CALLS tool calls at once")
            calls += LlmResponse.ToolCall(name, arguments.mapValues { (_, v) -> v.flat() }, id = null, argumentsJson = arguments.toString())
        }
        return LlmResponse.ToolCall.of(calls)
    }

    private fun JsonElement.flat(): String = if (this is JsonPrimitive) content else toString()

    private fun invalid(what: String) =
        LlmResponse.Error(LlmError.InvalidResponse("The on-device model's tool call could not be used: $what. Nothing was run."))
}
