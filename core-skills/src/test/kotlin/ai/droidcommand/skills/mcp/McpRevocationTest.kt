package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.bots.ScopedToolRunner
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.security.ApprovalPrompt
import ai.droidcommand.security.DefaultActionRisks
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A point where the fake network or the fake owner waits until the test lets it go on. */
private class Gate {
    val reached = CountDownLatch(1)
    private val release = CountDownLatch(1)

    fun pass() {
        reached.countDown()
        check(release.await(WAIT_SECONDS, TimeUnit.SECONDS)) { "the test never opened this gate" }
    }

    fun awaitReached() = assertTrue(reached.await(WAIT_SECONDS, TimeUnit.SECONDS), "never got to the gate")

    fun open() = release.countDown()
}

private const val WAIT_SECONDS = 10L
private const val HOME_URL = "https://home.test/mcp"
private const val LAB_URL = "https://lab.test/mcp"

/** Fake MCP servers behind one fake [HttpTransport]. Records every request; a request can be held at a [Gate]. */
private class FakeMcpNetwork : HttpTransport {
    /** "url method" for every JSON-RPC request and every DELETE, in order. */
    val requests = CopyOnWriteArrayList<String>()
    private val gates = ConcurrentHashMap<String, Gate>()
    private val tools = mapOf(
        HOME_URL to """[{"name":"search_notes","description":"Finds notes","annotations":{"readOnlyHint":true}},
            {"name":"add_note","description":"Adds a note","annotations":{"readOnlyHint":false,"destructiveHint":false}}]""",
        LAB_URL to """[{"name":"lookup","description":"Looks up","annotations":{"readOnlyHint":true}}]""",
    )

    /** The next [method] request to [url] waits at the returned gate (once). */
    fun hold(url: String, method: String): Gate = Gate().also { gates["$url $method"] = it }

    fun count(url: String, method: String): Int = requests.count { it == "$url $method" }

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        check(request.headers["Authorization"] == "Bearer $FAKE_TOKEN") { "the synthetic token was not sent" }
        if (request.method == "DELETE") {
            requests += "${request.url} DELETE"
            return HttpResponseSpec(200, emptyMap(), "")
        }
        val message = Json.parseToJsonElement(request.body!!).jsonObject
        val method = message["method"]!!.jsonPrimitive.content
        requests += "${request.url} $method"
        gates.remove("${request.url} $method")?.pass()
        if (method == "notifications/initialized") return HttpResponseSpec(202, emptyMap(), "")
        val id = message["id"]!!.jsonPrimitive.int
        val result = when (method) {
            "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
            "tools/list" -> """{"tools":${tools.getValue(request.url)}}"""
            "tools/call" -> """{"content":[{"type":"text","text":"done"}]}"""
            else -> error("unexpected method $method")
        }
        val headers = buildMap {
            if (method == "initialize") put("Mcp-Session-Id", "sess-1")
            put("Content-Type", "application/json")
        }
        return HttpResponseSpec(200, headers, """{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    companion object {
        const val FAKE_TOKEN = "synthetic-test-token"
    }
}

/** The owner at the approval dialog: says yes, and can be made to leave one question open until the test goes on. */
private class GatedApproval : ApprovalPrompt {
    val asked = AtomicInteger()
    private val gates = ConcurrentHashMap<Int, Gate>()

    /** The [nth] question (1 = the normal dialog, 2 = a write tool's own check) stays open until the gate opens. */
    fun holdQuestion(nth: Int): Gate = Gate().also { gates[nth] = it }

    override fun requestApproval(reason: String): Boolean {
        gates.remove(asked.incrementAndGet())?.pass()
        return true
    }

    override fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean = requestApproval(reason)
}

/**
 * Taking MCP access away must work at any moment: while servers are still answering a refresh, while the approval
 * dialog is open, and while a call is on the network.
 *
 * The rig is the phone's real Chat path below the screens: [McpToolCatalog] with real [HttpMcpToolSource]s over a
 * fake transport, the one [ToolRegistry], [SecureToolExecutor] and a helper bot's [ScopedToolRunner]. The three
 * settings actions do exactly what the app's `McpToolsRuntime` does (`setEnabled(false)` → [McpToolCatalog.clear],
 * `saveServers` → [McpToolCatalog.settingsChanged], `refresh` → [McpToolCatalog.refresh] with the live settings).
 */
class McpRevocationTest {
    private val home = McpServerConfig("home", "Home PC", HOME_URL)
    private val lab = McpServerConfig("lab", "Lab box", LAB_URL)
    private val net = FakeMcpNetwork()
    private val approval = GatedApproval()
    private val registry = ToolRegistry().apply {
        register(
            object : Tool {
                override val spec = ToolSpec(name = "notes", description = "built in")
                override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("built-in ran")
            },
        )
    }
    private val catalog = McpToolCatalog(registry, approval) { server ->
        HttpMcpToolSource(net, server.url, server.label, token = { FakeMcpNetwork.FAKE_TOKEN })
    }
    private val secure: ToolRunner = SecureToolExecutor(
        registry,
        ToolExecutor(registry, AgentStateMachine()),
        AgentStateMachine(),
        SecurityPolicyEnforcer(
            SecurityPolicy(
                grantedCategories = setOf(PermissionCategory.NETWORK),
                autoApprovalPolicy = DefaultActionRisks.policy(yoloMode = false),
            ),
        ),
        approval,
    )
    private val pool = Executors.newCachedThreadPool()

    // The owner's settings, as McpToolsRuntime keeps them.
    @Volatile
    private var enabled = true

    @Volatile
    private var servers = listOf(home, lab)

    private fun refresh(): List<McpServerStatus> = catalog.refresh { McpSettings(enabled, servers) }

    private fun saveServers(now: List<McpServerConfig>) {
        servers = now
        catalog.settingsChanged(McpSettings(enabled, now))
    }

    private fun turnMcpOff() {
        enabled = false
        catalog.clear()
    }

    /** An already-open helper bot. Like `ChatSession`, a throw from the runner is a failed tool call. */
    private fun bot(vararg allowed: String): (String) -> ToolResult {
        val runner = ScopedToolRunner(secure, allowed.toSet())
        return { tool ->
            try {
                runner.run(tool, mapOf("text" to "milk"), initiator = Initiator.AI)
            } catch (e: Exception) {
                ToolResult.Failure(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    private fun <T> background(block: () -> T): Future<T> = pool.submit<T> { block() }

    private fun <T> Future<T>.result(): T = get(WAIT_SECONDS, TimeUnit.SECONDS)

    /** Runs [block] and fails if it has not returned almost at once, i.e. if it waited for the held network. */
    private fun withoutWaiting(what: String, block: () -> Unit) {
        try {
            background(block).get(2, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            throw AssertionError("$what waited for a server that had not answered yet")
        }
    }

    private fun mcpToolsInRegistry(): List<String> = registry.list().map { it.name }.filter { it.startsWith("mcp_") }

    @AfterTest
    fun stop() {
        pool.shutdownNow()
    }

    @Test
    fun `turning MCP off during a slow discovery works at once and the late answer brings nothing back`() {
        val slow = net.hold(HOME_URL, "tools/list")
        val refreshing = background { refresh() }
        slow.awaitReached()

        withoutWaiting("Turning MCP off") { turnMcpOff() }
        assertTrue(mcpToolsInRegistry().isEmpty())
        slow.open()
        val statuses = refreshing.result()

        assertTrue(mcpToolsInRegistry().isEmpty(), "the old answer must not register tools")
        assertTrue(catalog.toolNames().isEmpty())
        assertTrue(statuses.none { it is McpServerStatus.Loaded }, "no ready status after MCP was turned off: $statuses")
        assertTrue(catalog.lastStatuses.none { it is McpServerStatus.Loaded })
        assertEquals(0, net.count(LAB_URL, "initialize"), "the next server is not contacted once MCP is off")
        assertEquals(1, net.count(HOME_URL, "tools/list"), "nothing is asked again while MCP is off")
    }

    @Test
    fun `turning one server off during discovery drops its late answer and still loads the others`() {
        val slow = net.hold(HOME_URL, "tools/list")
        val refreshing = background { refresh() }
        slow.awaitReached()

        withoutWaiting("Turning the server off") { saveServers(listOf(home.copy(enabled = false), lab)) }
        slow.open()
        val statuses = refreshing.result()

        assertEquals(listOf("mcp_lab_lookup"), mcpToolsInRegistry())
        assertIs<McpServerStatus.Skipped>(statuses.single { it.server.id == "home" })
        assertIs<McpServerStatus.Loaded>(statuses.single { it.server.id == "lab" })
        assertEquals(statuses, catalog.lastStatuses)
        assertEquals(1, net.count(HOME_URL, "tools/list"), "the turned-off server is not asked again")

        val run = bot("mcp_home_search_notes", "mcp_lab_lookup", "notes")
        assertIs<ToolResult.Failure>(run("mcp_home_search_notes"))
        assertEquals(0, net.count(HOME_URL, "tools/call"))
        assertIs<ToolResult.Success>(run("mcp_lab_lookup"))
        assertEquals("built-in ran", assertIs<ToolResult.Success>(run("notes")).output)
    }

    @Test
    fun `removing a server during discovery drops its late answer`() {
        val slow = net.hold(HOME_URL, "tools/list")
        val refreshing = background { refresh() }
        slow.awaitReached()

        saveServers(listOf(lab))
        slow.open()
        val statuses = refreshing.result()

        assertEquals(listOf("mcp_lab_lookup"), mcpToolsInRegistry())
        assertEquals(listOf("lab"), statuses.map { it.server.id })
    }

    @Test
    fun `an open helper bot cannot call a server that was turned off, and can again after it is turned back on`() {
        refresh()
        val run = bot("mcp_home_search_notes", "mcp_lab_lookup", "notes")
        assertIs<ToolResult.Success>(run("mcp_home_search_notes"))
        assertEquals(1, net.count(HOME_URL, "tools/call"))

        // No refresh here: saving the setting alone must be enough.
        saveServers(listOf(home.copy(enabled = false), lab))

        assertIs<ToolResult.Failure>(run("mcp_home_search_notes"))
        assertEquals(1, net.count(HOME_URL, "tools/call"), "no new call reached the turned-off server")
        assertTrue(catalog.lastStatuses.none { it.server.id == "home" && it is McpServerStatus.Loaded })
        assertIs<ToolResult.Success>(run("mcp_lab_lookup"))
        assertIs<ToolResult.Success>(run("notes"))

        saveServers(listOf(home, lab))
        refresh()

        assertIs<ToolResult.Success>(run("mcp_home_search_notes"))
        assertEquals(2, net.count(HOME_URL, "tools/call"))
        assertIs<ToolResult.Failure>(bot("notes")("mcp_home_search_notes"), "the bot's own allow-list still applies")
    }

    @Test
    fun `saying yes after the server was turned off sends nothing`() {
        // Question 1 is the normal approval dialog; question 2 is the write tool's own "this changes something" check.
        for (question in 1..2) {
            enabled = true
            saveServers(listOf(home, lab))
            refresh()
            val before = net.count(HOME_URL, "tools/call")
            approval.asked.set(0)
            val dialog = approval.holdQuestion(question)
            val run = bot("mcp_home_add_note")
            val calling = background { run("mcp_home_add_note") }
            dialog.awaitReached()

            if (question == 1) saveServers(listOf(home.copy(enabled = false), lab)) else turnMcpOff()
            dialog.open()

            assertIs<ToolResult.Failure>(calling.result(), "question $question")
            assertEquals(before, net.count(HOME_URL, "tools/call"), "approved after revocation (question $question), yet a call was sent")
        }
    }

    @Test
    fun `a write tool still asks twice and is sent once when nothing is revoked`() {
        refresh()
        assertIs<ToolResult.Success>(bot("mcp_home_add_note")("mcp_home_add_note"))
        assertEquals(2, approval.asked.get(), "the normal dialog, then the tool's own write check")
        assertEquals(1, net.count(HOME_URL, "tools/call"))
    }

    @Test
    fun `a call already on the network finishes, revoking does not wait for it, and no further call starts`() {
        refresh()
        val run = bot("mcp_home_search_notes")
        val onTheWire = net.hold(HOME_URL, "tools/call")
        val calling = background { run("mcp_home_search_notes") }
        onTheWire.awaitReached()

        withoutWaiting("Turning MCP off") { turnMcpOff() }
        assertIs<ToolResult.Failure>(run("mcp_home_search_notes"))
        onTheWire.open()

        assertEquals("done", assertIs<ToolResult.Success>(calling.result()).output)
        assertEquals(1, net.count(HOME_URL, "tools/call"), "only the call that was already sent")
    }

    @Test
    fun `off by default contacts nothing`() {
        enabled = false
        val statuses = refresh()
        assertTrue(statuses.all { it is McpServerStatus.Skipped })
        assertTrue(net.requests.isEmpty())
        assertTrue(mcpToolsInRegistry().isEmpty())
    }
}
