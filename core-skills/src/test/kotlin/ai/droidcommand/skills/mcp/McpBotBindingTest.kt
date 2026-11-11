package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.bots.ScopedToolRunner
import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.security.DefaultActionRisks
import ai.droidcommand.security.InMemoryAuditLog
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OLD_URL = "https://old.test/mcp"
private const val NEW_URL = "https://new.test/mcp"
private const val TOKEN = "synthetic-test-token"

/** Fake MCP servers behind one fake transport. Each address has its own tool list; every tool call is recorded. */
private class FakeServers : HttpTransport {
    /** JSON text of the `tools` array each address answers with. */
    val tools = mutableMapOf<String, String>()

    /** "address remoteToolName" for every `tools/call` that reached a server, in order. */
    val calls = CopyOnWriteArrayList<String>()

    fun callsTo(url: String): List<String> = calls.filter { it.startsWith("$url ") }.map { it.substringAfter(' ') }

    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        check(request.headers["Authorization"] == "Bearer $TOKEN") { "the synthetic token was not sent" }
        if (request.method == "DELETE") return HttpResponseSpec(200, emptyMap(), "")
        val message = Json.parseToJsonElement(request.body!!).jsonObject
        val method = message["method"]!!.jsonPrimitive.content
        if (method == "notifications/initialized") return HttpResponseSpec(202, emptyMap(), "")
        val id = message["id"]!!.jsonPrimitive.int
        val result = when (method) {
            "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"1"}}"""
            "tools/list" -> """{"tools":${tools.getValue(request.url)}}"""
            "tools/call" -> {
                calls += request.url + " " + message["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
                """{"content":[{"type":"text","text":"done"}]}"""
            }
            else -> error("unexpected method $method")
        }
        return HttpResponseSpec(200, mapOf("Content-Type" to "application/json"), """{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }
}

private fun toolList(vararg names: String, hints: String = """{"readOnlyHint":true}"""): String =
    names.joinToString(",", "[", "]") { """{"name":"$it","description":"d","annotations":$hints}""" }

/** What Bot maker keeps for one bot's tools: the ticked names and, for MCP tools, what each is tied to. */
private data class SavedBot(val tools: Set<String>, val bindings: Map<String, McpToolBinding> = emptyMap())

/**
 * One run of the app, below the screens: the one [ToolRegistry], [McpToolCatalog] with real [HttpMcpToolSource]s,
 * [SecureToolExecutor] with an audit log, and a helper bot's [ScopedToolRunner]. A second [Phone] over the same
 * [FakeServers] and the same saved data is the app after a restart.
 *
 * [save] and [open] are the same calls the app makes: Bot maker's save is [McpSelections.bind], and Chat's
 * "pick up bot" is [McpSelections.resolve] → `ScopedToolRunner(secure, allowed)` and the registry filtered to
 * `allowed`.
 */
private class Phone(val net: FakeServers, var servers: List<McpServerConfig>) {
    val approval = RecordingApproval()
    val audit = InMemoryAuditLog()
    val registry = ToolRegistry().apply {
        register(
            object : Tool {
                override val spec = ToolSpec(name = "notes", description = "built in")
                override fun execute(input: Map<String, String>): ToolResult = ToolResult.Success("built-in ran")
            },
        )
    }
    val catalog = McpToolCatalog(registry, approval) { server -> HttpMcpToolSource(net, server.url, server.label, token = { TOKEN }) }
    val secure = SecureToolExecutor(
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
        auditLog = audit,
    )

    fun refresh(): List<McpServerStatus> = catalog.refresh { McpSettings(true, servers) }

    /** The owner saves a changed server list in Settings (`McpToolsRuntime.saveServers`), then tools reload. */
    fun saveServers(now: List<McpServerConfig>) {
        servers = now
        catalog.settingsChanged(McpSettings(true, now))
        refresh()
    }

    /** Settings → add server, as `McpServersViewModel.addServer` builds it. */
    fun addServer(label: String, url: String): McpServerConfig {
        val server = McpServerConfig(
            id = McpServerConfig.newId(label, servers.map { it.id }.toSet()),
            label = label,
            url = url,
            instanceId = McpServerConfig.newInstanceId(),
        )
        saveServers(servers + server)
        return server
    }

    /** Bot maker: the owner ticks [names] and saves. */
    fun save(vararg names: String): SavedBot {
        val bindings = McpSelections.bind(names.toSet(), catalog.loadedTools())
        return SavedBot(names.filter { !McpBridgedTool.isMcpTool(it) || it in bindings }.toSet(), bindings)
    }

    fun resolve(bot: SavedBot): McpResolution = McpSelections.resolve(
        bot.tools,
        bot.bindings,
        catalog.loadedTools(),
        servers,
        catalog.lastStatuses.filterIsInstance<McpServerStatus.Loaded>().map { it.server.instanceId }.toSet(),
    )

    /** Chat picks the bot up. */
    fun open(bot: SavedBot): OpenBot {
        val allowed = resolve(bot).allowed
        return OpenBot(ScopedToolRunner(secure, allowed), registry.list().map { it.name }.filter { it in allowed }.toSet())
    }

    fun mcpNames(): List<String> = catalog.toolNames().sorted()
}

/** A bot running in Chat: the tools the model is offered, and a way to make the model call one. */
private class OpenBot(private val runner: ScopedToolRunner, val offered: Set<String>) {
    /** Like `ChatSession`, a throw from the runner is a failed tool call. */
    fun call(tool: String): ToolResult = try {
        runner.run(tool, mapOf("text" to "milk"), initiator = Initiator.AI)
    } catch (e: Exception) {
        ToolResult.Failure(e.message ?: e.javaClass.simpleName, e)
    }
}

/**
 * A helper bot's saved MCP tool stays tied to the server entry and the exact remote tool the owner ticked.
 * A replacement server or a look-alike name gets nothing until the owner ticks it. Every "refused" case also
 * checks that the wrong server (or wrong remote tool) received zero calls.
 */
class McpBotBindingTest {
    private val net = FakeServers().apply {
        tools[OLD_URL] = toolList("search")
        tools[NEW_URL] = toolList("search")
    }

    private fun phoneWithOldServer(): Pair<Phone, McpServerConfig> {
        val phone = Phone(net, emptyList())
        val old = phone.addServer("Notes", OLD_URL)
        return phone to old
    }

    // ---- 1. A different server under the same label ----

    @Test
    fun `a replacement server under the same label is not offered or callable, in the same run or after a restart`() {
        val (phone, old) = phoneWithOldServer()
        val bot = phone.save("notes", "mcp_notes_search")
        val alreadyOpen = phone.open(bot)
        assertIs<ToolResult.Success>(alreadyOpen.call("mcp_notes_search"))
        assertEquals(listOf("search"), net.callsTo(OLD_URL))

        // The owner removes the server, then adds another address under the same label. It gets the same id.
        phone.saveServers(emptyList())
        val replacement = phone.addServer("Notes", NEW_URL)
        assertEquals(old.id, replacement.id)
        assertNotEquals(old.instanceId, replacement.instanceId)

        // Same run: the name the open bot holds must not start meaning the new server's tool.
        assertFalse("mcp_notes_search" in phone.mcpNames(), "the old name must not be handed to the replacement")
        assertIs<ToolResult.Failure>(alreadyOpen.call("mcp_notes_search"))

        // The bot opened again: nothing from the replacement, under any name it has.
        val now = phone.resolve(bot)
        assertEquals(listOf("mcp_notes_search"), now.needsReselect)
        assertEquals(setOf("notes"), now.allowed)
        val reopened = phone.open(bot)
        assertEquals(setOf("notes"), reopened.offered)
        (phone.mcpNames() + "mcp_notes_search").forEach { assertIs<ToolResult.Failure>(reopened.call(it)) }

        // After a restart the replacement's tool has exactly the saved name. Still refused.
        val restarted = Phone(net, phone.servers).apply { refresh() }
        assertEquals(listOf("mcp_notes_search"), restarted.mcpNames())
        assertEquals(listOf("mcp_notes_search"), restarted.resolve(bot).needsReselect)
        val afterRestart = restarted.open(bot)
        assertEquals(setOf("notes"), afterRestart.offered)
        val refused = afterRestart.call("mcp_notes_search")
        assertIs<ToolResult.Failure>(refused)
        assertTrue("allowed-tool list" in refused.reason, "refused by the bot's own list, before the security executor")
        assertTrue(restarted.approval.asked.isEmpty(), "the owner is not even asked about a tool the bot may not use")

        assertEquals(emptyList(), net.callsTo(NEW_URL), "the replacement server must receive zero tool calls")
        assertEquals(listOf("search"), net.callsTo(OLD_URL))
        assertIs<ToolResult.Success>(afterRestart.call("notes"))
    }

    @Test
    fun `the replacement works once the owner ticks it, with the normal approval and an audit record`() {
        val (phone, _) = phoneWithOldServer()
        phone.saveServers(emptyList())
        phone.addServer("Notes", NEW_URL)
        val restarted = Phone(net, phone.servers).apply { refresh() }

        val bot = restarted.save("mcp_notes_search")
        assertEquals(NEW_URL, restarted.servers.single { it.instanceId == bot.bindings.getValue("mcp_notes_search").serverInstance }.url)
        assertIs<ToolResult.Success>(restarted.open(bot).call("mcp_notes_search"))

        assertEquals(listOf("search"), net.callsTo(NEW_URL))
        assertEquals(1, restarted.approval.asked.size, "a read-only MCP tool still asks once through the normal dialog")
        assertTrue(restarted.audit.all().any { "mcp_notes_search" in it.toString() }, "the call is in the audit log")
    }

    // ---- 2. Two remote names that clean up to the same local name ----

    @Test
    fun `look-alike remote names keep their own names when the server lists them in the other order`() {
        net.tools[OLD_URL] = toolList("get.item", "get_item")
        val (phone, old) = phoneWithOldServer()
        val names = phone.mcpNames()
        assertEquals(2, names.size)
        assertFalse(McpBridgedTool.toolName(old.id, "get.item") in names, "a name two tools share is given to neither")
        val dotted = phone.catalog.loadedTools().single { it.binding.remoteName == "get.item" }.name
        val bot = phone.save(dotted)
        val open = phone.open(bot)
        assertIs<ToolResult.Success>(open.call(dotted))

        net.tools[OLD_URL] = toolList("get_item", "get.item")
        phone.refresh()
        assertEquals(names, phone.mcpNames())
        assertIs<ToolResult.Success>(open.call(dotted))

        val restarted = Phone(net, phone.servers).apply { refresh() }
        assertEquals(names, restarted.mcpNames())
        assertIs<ToolResult.Success>(restarted.open(bot).call(dotted))

        assertEquals(listOf("get.item", "get.item", "get.item"), net.callsTo(OLD_URL))
    }

    @Test
    fun `long remote names that are cut to the same local name are told apart too`() {
        val first = "x".repeat(70) + "_first"
        val second = "x".repeat(70) + "_second"
        assertEquals(McpBridgedTool.toolName("notes", first), McpBridgedTool.toolName("notes", second))
        net.tools[OLD_URL] = toolList(first, second)
        val (phone, _) = phoneWithOldServer()
        val name = phone.catalog.loadedTools().single { it.binding.remoteName == second }.name
        assertTrue(name.length <= 64)
        val bot = phone.save(name)

        net.tools[OLD_URL] = toolList(second, first)
        val restarted = Phone(net, phone.servers).apply { refresh() }
        assertIs<ToolResult.Success>(restarted.open(bot).call(name))
        assertEquals(listOf(second), net.callsTo(OLD_URL))
    }

    @Test
    fun `a look-alike tool that appears later never takes over a saved tool's name`() {
        net.tools[OLD_URL] = toolList("get.item")
        val (phone, _) = phoneWithOldServer()
        val bot = phone.save("mcp_notes_get_item")
        val alreadyOpen = phone.open(bot)

        // The server now also offers "get_item", listed first. Before the fix it took the name "mcp_notes_get_item".
        net.tools[OLD_URL] = toolList("get_item", "get.item")
        phone.refresh()
        assertIs<ToolResult.Failure>(alreadyOpen.call("mcp_notes_get_item"))

        // Opened again (and after a restart) the bot still reaches the tool it was tied to, under its new name.
        for (app in listOf(phone, Phone(net, phone.servers).apply { refresh() })) {
            val now = app.resolve(bot)
            assertTrue(now.needsReselect.isEmpty())
            val current = now.current.getValue("mcp_notes_get_item")
            assertNotEquals("mcp_notes_get_item", current)
            assertIs<ToolResult.Success>(app.open(bot).call(current))
            assertIs<ToolResult.Failure>(app.open(bot).call(app.catalog.loadedTools().single { it.binding.remoteName == "get_item" }.name))
        }
        assertEquals(listOf("get.item", "get.item"), net.callsTo(OLD_URL), "\"get_item\" must receive zero calls")
    }

    @Test
    fun `a remote name the server lists twice is left out and said so`() {
        net.tools[OLD_URL] = """[{"name":"wipe","annotations":{"readOnlyHint":true}},{"name":"wipe"},{"name":"search","annotations":{"readOnlyHint":true}}]"""
        val (phone, _) = phoneWithOldServer()
        assertEquals(listOf("mcp_notes_search"), phone.mcpNames())
        val status = assertIs<McpServerStatus.Loaded>(phone.catalog.lastStatuses.single())
        assertEquals(1, status.leftOut)
        assertTrue("same name twice" in status.words)
    }

    // ---- The same server keeps its bots ----

    @Test
    fun `restart, refresh and turning the same server off and on keep the saved tool`() {
        val (phone, old) = phoneWithOldServer()
        val bot = phone.save("mcp_notes_search")

        phone.refresh()
        assertIs<ToolResult.Success>(phone.open(bot).call("mcp_notes_search"))

        val restarted = Phone(net, phone.servers).apply { refresh() }
        assertIs<ToolResult.Success>(restarted.open(bot).call("mcp_notes_search"))

        restarted.saveServers(listOf(old.copy(enabled = false)))
        val off = restarted.resolve(bot)
        assertEquals(listOf("mcp_notes_search"), off.unavailable)
        assertTrue(off.needsReselect.isEmpty(), "a server that is only turned off does not need picking again")
        assertIs<ToolResult.Failure>(restarted.open(bot).call("mcp_notes_search"))

        restarted.saveServers(listOf(old.copy(enabled = true)))
        assertIs<ToolResult.Success>(restarted.open(bot).call("mcp_notes_search"))

        assertEquals(listOf("search", "search", "search"), net.callsTo(OLD_URL))
    }

    @Test
    fun `a write tool tied to its server still asks twice and sends nothing on no`() {
        net.tools[OLD_URL] = toolList("add_note", hints = """{"readOnlyHint":false,"destructiveHint":false}""")
        val (phone, _) = phoneWithOldServer()
        val bot = phone.save("mcp_notes_add_note")

        assertIs<ToolResult.Success>(phone.open(bot).call("mcp_notes_add_note"))
        assertEquals(2, phone.approval.asked.size, "the normal dialog, then the write tool's own question")

        phone.approval.answer = false
        assertIs<ToolResult.Failure>(phone.open(bot).call("mcp_notes_add_note"))
        assertEquals(listOf("add_note"), net.callsTo(OLD_URL))
    }

    // ---- Bots saved before tools were tied to a server ----

    /** A server as it reads from settings saved by the older version: no instance id of its own. */
    private fun oldSavedServer(url: String) = McpServerConfig("notes", "Notes", url)

    @Test
    fun `an older bot keeps a tool when it is clear which one was meant, and is then tied to it`() {
        net.tools[OLD_URL] = toolList("get.item")
        val phone = Phone(net, listOf(oldSavedServer(OLD_URL))).apply { refresh() }
        val older = SavedBot(setOf("notes", "mcp_notes_get_item"))

        val now = phone.resolve(older)
        assertEquals(setOf("notes", "mcp_notes_get_item"), now.allowed)
        assertEquals(mapOf("mcp_notes_get_item" to McpToolBinding("pre-notes", "get.item")), now.adopted)
        assertIs<ToolResult.Success>(phone.open(older).call("mcp_notes_get_item"))

        // Bot maker saves the binding. From then on a look-alike that turns up cannot be taken for it.
        val tied = older.copy(bindings = now.adopted)
        net.tools[OLD_URL] = toolList("get_item", "get.item")
        val restarted = Phone(net, phone.servers).apply { refresh() }
        val current = restarted.resolve(tied).current.getValue("mcp_notes_get_item")
        assertIs<ToolResult.Success>(restarted.open(tied).call(current))
        assertEquals(listOf("get.item", "get.item"), net.callsTo(OLD_URL))
    }

    @Test
    fun `an older bot must pick again when two tools could be the one it saved`() {
        net.tools[OLD_URL] = toolList("get_item", "get.item")
        val phone = Phone(net, listOf(oldSavedServer(OLD_URL))).apply { refresh() }
        val older = SavedBot(setOf("notes", "mcp_notes_get_item"))

        val now = phone.resolve(older)
        assertEquals(listOf("mcp_notes_get_item"), now.needsReselect)
        assertTrue(now.adopted.isEmpty())
        val open = phone.open(older)
        assertEquals(setOf("notes"), open.offered)
        (phone.mcpNames() + "mcp_notes_get_item").forEach { assertIs<ToolResult.Failure>(open.call(it)) }
        assertEquals(emptyList(), net.callsTo(OLD_URL))
        assertTrue("Pick again: mcp_notes_get_item" in McpSelections.reselectWords(now.needsReselect)!!)
    }

    @Test
    fun `an older bot does not follow a server that replaced the one it knew`() {
        val phone = Phone(net, listOf(oldSavedServer(OLD_URL))).apply { refresh() }
        val older = SavedBot(setOf("mcp_notes_search"))
        phone.saveServers(emptyList())
        phone.addServer("Notes", NEW_URL)

        for (app in listOf(phone, Phone(net, phone.servers).apply { refresh() })) {
            val now = app.resolve(older)
            assertEquals(listOf("mcp_notes_search"), now.needsReselect)
            assertTrue(now.adopted.isEmpty())
            val open = app.open(older)
            assertTrue(open.offered.isEmpty())
            (app.mcpNames() + "mcp_notes_search").forEach { assertIs<ToolResult.Failure>(open.call(it)) }
        }
        assertEquals(emptyList(), net.calls.toList())
    }

    @Test
    fun `an older bot whose server is only turned off waits instead of asking to pick again`() {
        val phone = Phone(net, listOf(oldSavedServer(OLD_URL).copy(enabled = false))).apply { refresh() }
        val now = phone.resolve(SavedBot(setOf("mcp_notes_search")))
        assertEquals(listOf("mcp_notes_search"), now.unavailable)
        assertTrue(now.needsReselect.isEmpty())
        assertTrue(now.allowed.isEmpty())
    }

    // ---- Small pieces ----

    @Test
    fun `saving ties only tools that are loaded and never a built-in tool`() {
        val (phone, old) = phoneWithOldServer()
        val bot = phone.save("notes", "mcp_notes_search", "mcp_notes_gone")
        assertEquals(setOf("notes", "mcp_notes_search"), bot.tools)
        assertEquals(mapOf("mcp_notes_search" to McpToolBinding(old.instanceId, "search")), bot.bindings)
        assertNull(McpSelections.reselectWords(emptyList()))
    }

    @Test
    fun `long names depend on the server entry and the exact remote name, never on order`() {
        val a = McpServerConfig("notes", "Notes", OLD_URL, instanceId = "one")
        val b = McpServerConfig("notes", "Notes", NEW_URL, instanceId = "two")
        assertEquals(McpBridgedTool.longName(a, "get.item"), McpBridgedTool.longName(a, "get.item"))
        assertNotEquals(McpBridgedTool.longName(a, "get.item"), McpBridgedTool.longName(a, "get_item"))
        assertNotEquals(McpBridgedTool.longName(a, "get.item"), McpBridgedTool.longName(b, "get.item"))
        assertTrue(McpBridgedTool.longName(a, "y".repeat(200)).length <= 64)
        assertEquals("notes", McpBridgedTool.serverIdOf("mcp_notes_get_item"))
        assertNull(McpBridgedTool.serverIdOf("notes"))
        assertNull(McpBridgedTool.serverIdOf("mcp_Notes!_x"))
    }
}
