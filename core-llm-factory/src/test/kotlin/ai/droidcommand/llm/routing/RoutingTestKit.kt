package ai.droidcommand.llm.routing

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.bots.ScopedToolRunner
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import ai.droidcommand.llm.local.InferenceBackend
import ai.droidcommand.llm.pchost.FakePcServer
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.StreamingHttpTransport
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import java.net.URI
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/** FAKE on-device backend: no llama.cpp. Records what it was asked and emits fixed tokens. */
class FakeBackend(private val tokens: List<String> = listOf("device ", "reply")) : InferenceBackend {
    override val kind = BackendKind.CPU
    val seen = CopyOnWriteArrayList<GenerationRequest>()
    var stopRequests = 0

    override fun load(modelPath: String, contextTokens: Int) = Unit

    override fun generate(request: GenerationRequest, onToken: (String) -> Boolean) {
        seen += request
        for (token in tokens) if (!onToken(token)) return
    }

    override fun requestStop() {
        stopRequests++
    }

    override fun unload() = Unit
}

/**
 * Passes every request to [delegate] and records the destination, so a test can assert that a place the policy
 * forbids received zero requests. [forbid] makes a request to a matching URL a test failure at the moment it is made.
 */
class RecordingTransport(private val delegate: StreamingHttpTransport, private val forbid: (String) -> Boolean = { false }) : StreamingHttpTransport {
    val urls = CopyOnWriteArrayList<String>()

    fun hosts(): Set<String> = urls.map { URI(it).let { u -> "${u.host}:${u.port}" } }.toSet()

    fun requestsTo(port: Int): List<String> = urls.filter { URI(it).port == port }

    private fun record(request: HttpRequestSpec) {
        urls += request.url
        check(!forbid(request.url)) { "a forbidden destination was contacted: ${request.url}" }
    }

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        record(request)
        return delegate.send(request)
    }

    override fun sendStreaming(request: HttpRequestSpec, onLine: (String) -> Unit): HttpResponseSpec {
        record(request)
        return delegate.sendStreaming(request, onLine)
    }
}

/** One tool call as a server streams it: [id] on the opening fragment, then the argument text in [fragments]. */
class StreamedCall(val id: String?, val name: String, val fragments: List<String>)

/**
 * A FAKE model server on a real loopback socket (no model). It answers the capability requests the app makes
 * (`/api/show`, `/api/ps` as Ollama; `/props`, `/v1/models` as a llama.cpp server) from the fields below, and each
 * `/v1/chat/completions` request with the next entry of [chat] (a plain "ok" reply when the script is empty).
 */
class FakeModelServer(
    var capabilities: List<String>? = listOf("completion", "tools"),
    var loadedContext: Int? = 4096,
    var remoteHost: String? = null,
    /** True: answers `/props` like llama.cpp. False: an Ollama server, where `/props` does not exist. */
    val llamaCpp: Boolean = false,
    var llamaSupportsTools: Boolean = true,
    /** False: an OpenAI-compatible server with neither `/props` nor `/api/show` (location and tools unreported). */
    val reportsAnything: Boolean = true,
    val model: String = "qwen3:4b",
) : AutoCloseable {
    val chat = ConcurrentLinkedDeque<(FakePcServer.Request, FakePcServer.Connection) -> Unit>()

    val server = FakePcServer { request, connection ->
        when (request.path) {
            "/api/show" -> if (!reportsAnything || llamaCpp) connection.respond(404, "{}") else connection.respond(200, show())
            "/api/ps" -> connection.respond(200, """{"models":[${loadedContext?.let { """{"name":"$model","model":"$model","context_length":$it}""" }.orEmpty()}]}""")
            "/props" -> if (llamaCpp) connection.respond(200, props()) else connection.respond(404, """{"error":"not found"}""")
            "/v1/models" -> connection.respond(200, """{"object":"list","data":[{"id":"$model","owned_by":"local"}]}""")
            "/v1/chat/completions" -> (chat.pollFirst() ?: text("ok"))(request, connection)
            else -> connection.respond(404, "{}")
        }
    }
    val address: String get() = server.address
    val port: Int get() = server.port

    /** The chat requests received, in order. */
    val chats: List<FakePcServer.Request> get() = server.requests.filter { it.path == "/v1/chat/completions" }

    private fun show(): String {
        val caps = capabilities?.let { list -> ""","capabilities":[${list.joinToString(",") { "\"$it\"" }}]""" }.orEmpty()
        val remote = remoteHost?.let { ""","remote_host":"$it","remote_model":"$model"""" }.orEmpty()
        return """{"parameters":"temperature 0.7","model_info":{"qwen3.context_length":40960}$caps$remote}"""
    }

    private fun props(): String =
        """{"default_generation_settings":{"n_ctx":${loadedContext ?: 4096}},"model_path":"/models/$model.gguf",""" +
            """"chat_template_caps":{"supports_tools":$llamaSupportsTools,"supports_tool_calls":$llamaSupportsTools}}"""

    /** Returns only once the port really refuses connections, so "the PC is off" is true when the test goes on. */
    override fun close() {
        val port = server.port
        server.close()
        repeat(200) {
            val open = runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess
            if (!open) return
            Thread.sleep(10)
        }
    }

    companion object {
        fun text(vararg parts: String): (FakePcServer.Request, FakePcServer.Connection) -> Unit = { _, connection ->
            connection.startStream()
            parts.forEach(connection::delta)
            connection.finish()
        }

        /** Streams [parts], then the PC vanishes: no end marker. */
        fun dropAfter(vararg parts: String): (FakePcServer.Request, FakePcServer.Connection) -> Unit = { _, connection ->
            connection.startStream()
            parts.forEach(connection::delta)
            connection.drop()
        }

        /** Tool calls streamed the way OpenAI-compatible servers do: an opening fragment per call, then argument pieces interleaved. */
        fun toolCalls(vararg calls: StreamedCall): (FakePcServer.Request, FakePcServer.Connection) -> Unit = { _, connection ->
            connection.startStream()
            calls.forEachIndexed { index, call ->
                val id = call.id?.let { """"id":"$it",""" }.orEmpty()
                connection.chunk("""data: {"choices":[{"delta":{"tool_calls":[{"index":$index,$id"type":"function","function":{"name":"${call.name}","arguments":""}}]}}]}""" + "\n\n")
            }
            val longest = calls.maxOf { it.fragments.size }
            for (piece in 0 until longest) {
                calls.forEachIndexed { index, call ->
                    call.fragments.getOrNull(piece)?.let { fragment ->
                        val escaped = fragment.replace("\\", "\\\\").replace("\"", "\\\"")
                        connection.chunk("""data: {"choices":[{"delta":{"tool_calls":[{"index":$index,"function":{"arguments":"$escaped"}}]}}]}""" + "\n\n")
                    }
                }
            }
            connection.chunk("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""" + "\n\n")
            connection.chunk("data: [DONE]\n\n")
            connection.endStream()
        }
    }
}

/**
 * The app's real tool path for a helper bot: [ScopedToolRunner] (the bot's allow-list) in front of
 * [SecureToolExecutor] (policy, approval prompt, audit log). The tools themselves are test doubles that count runs.
 */
class SecureTools(allowed: Set<String>, private val approve: (String) -> Boolean = { true }) {
    class Counting(name: String, level: SecurityLevel = SecurityLevel.SENSITIVE, private val onRun: () -> Unit = {}) : Tool {
        override val spec = ToolSpec(name = name, description = "Test tool $name", securityLevel = level)
        val inputs = CopyOnWriteArrayList<Map<String, String>>()

        override fun execute(input: Map<String, String>): ToolResult {
            inputs += input
            onRun()
            return ToolResult.Success("${spec.name} done #${inputs.size}")
        }
    }

    val registry = ToolRegistry()
    val audit = InMemoryAuditLog()
    val approvalsAsked = CopyOnWriteArrayList<String>()
    private val machine = AgentStateMachine()
    private val secure: ToolRunner = SecureToolExecutor(
        registry,
        ToolExecutor(registry, machine),
        machine,
        SecurityPolicyEnforcer(SecurityPolicy()),
        object : ApprovalPrompt {
            override fun requestApproval(reason: String): Boolean = error("the three-argument form is the one the executor calls")

            override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean {
                approvalsAsked += toolName
                return approve(toolName)
            }
        },
        auditLog = audit,
    )

    /** What Chat is given for the bot. */
    val runner: ToolRunner = ScopedToolRunner(secure, allowed)

    fun add(tool: Counting): Counting = tool.also(registry::register)

    fun specs(vararg names: String): List<ToolSpec> = names.map { registry.get(it).spec }
}
