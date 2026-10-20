package ai.droidcommand.app.fleet

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.fleet.messenger.BridgeResult
import ai.droidcommand.fleet.messenger.ExternalNodeTransport
import ai.droidcommand.fleet.messenger.InMemoryPairedNodeStore
import ai.droidcommand.fleet.messenger.PairedNode
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.fleet.messenger.ReplyExpectation
import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import ai.droidcommand.security.FleetReachability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val ROOTFORGE_PAIRED = PairedNode.of("ai.rootforge.os", "rootforge", "a".repeat(64))

private class FakeExternalNodeTransport(
    private val reply: (PairedNode, String, JsonObject) -> BridgeResult<JsonObject>,
) : ExternalNodeTransport {
    var lastOperation: String? = null
        private set
    var lastParameters: JsonObject? = null
        private set

    override fun call(
        node: PairedNode,
        operation: String,
        parameters: JsonObject,
        timeoutMs: Int,
        expect: ReplyExpectation,
    ): BridgeResult<JsonObject> {
        lastOperation = operation
        lastParameters = parameters
        return reply(node, operation, parameters)
    }
}

class MessengerFleetTransportTest {
    private val rootforge = FleetNodes.nodes().single { it.id == "rootforge" }
    private val fileManager = FleetNodes.nodes().single { it.id == "file-manager" }

    @Test
    fun `an unpaired node is pairing required, never reachable or offline`() {
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(), FakeExternalNodeTransport { _, _, _ -> error("must not be called") })
        val r = MessengerFleetTransport(gateway) { true }.probe(rootforge)
        assertEquals(FleetReachability.UNKNOWN, r.reachability)
        assertTrue(r.detail.startsWith("Pairing required"))
        assertTrue(!r.commandChannel)
    }

    @Test
    fun `a paired node that answers capabilities is reachable with a command channel`() {
        val fake = FakeExternalNodeTransport { _, _, _ -> RootForgeResult.Success(JsonObject(emptyMap())) }
        val gateway = PairedNodeGateway(
            InMemoryPairedNodeStore(listOf(ROOTFORGE_PAIRED)),
            fake,
        )
        val r = MessengerFleetTransport(gateway) { true }.probe(rootforge)
        assertEquals(FleetReachability.REACHABLE, r.reachability)
        assertTrue(r.commandChannel)
        assertEquals("rootforge.capabilities.get", fake.lastOperation)
        assertTrue(r.detail.contains("rootforge.capabilities.get"))
    }

    @Test
    fun `a transport failure while paired is offline`() {
        val gateway = PairedNodeGateway(
            InMemoryPairedNodeStore(listOf(ROOTFORGE_PAIRED)),
            FakeExternalNodeTransport { _, _, _ -> RootForgeResult.Failure(FailureKind.TRANSPORT, "service did not bind") },
        )
        val r = MessengerFleetTransport(gateway) { true }.probe(rootforge)
        assertEquals(FleetReachability.OFFLINE, r.reachability)
        assertTrue(r.detail.contains("service did not bind"))
    }

    @Test
    fun `a node with no catalogued bridge operations is unknown, not a crash`() {
        // "termux" is TERMUX_IPC, not in ExternalNodeCatalog.
        val termux = FleetNodes.nodes().single { it.id == "termux" }
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(), FakeExternalNodeTransport { _, _, _ -> error("must not be called") })
        val r = MessengerFleetTransport(gateway) { true }.probe(termux)
        assertEquals(FleetReachability.UNKNOWN, r.reachability)
        assertTrue(r.detail.contains("No bridge operations catalogued"))
    }

    @Test
    fun `sendCommand passes the capabilityId through as the bridge operation unchanged`() {
        val fake = FakeExternalNodeTransport { _, _, _ -> RootForgeResult.Success(buildJsonObject { put("ok", true) }) }
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(listOf(ROOTFORGE_PAIRED)), fake)
        val result = MessengerFleetTransport(gateway) { true }.sendCommand(rootforge, "rootforge.adb.shell", """{"command":"id"}""")
        assertIs<ToolResult.Success>(result)
        assertEquals("rootforge.adb.shell", fake.lastOperation)
        assertEquals("id", fake.lastParameters?.get("command")?.toString()?.trim('"'))
    }

    @Test
    fun `sendCommand to an unpaired node fails without reaching the transport`() {
        val fake = FakeExternalNodeTransport { _, _, _ -> error("must not be called") }
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(), fake)
        val result = MessengerFleetTransport(gateway) { true }.sendCommand(fileManager, "filemanager.status", "{}")
        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("untrusted_node", ignoreCase = true) || result.reason.contains("not paired", ignoreCase = true))
    }

    @Test
    fun `sendCommand rejects input that is not a JSON object`() {
        val gateway = PairedNodeGateway(InMemoryPairedNodeStore(listOf(ROOTFORGE_PAIRED)), FakeExternalNodeTransport { _, _, _ -> error("must not be called") })
        val result = MessengerFleetTransport(gateway) { true }.sendCommand(rootforge, "rootforge.adb.shell", "not json")
        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("not a JSON object"))
    }

    @Test
    fun `a remote failure is reported with its category`() {
        val gateway = PairedNodeGateway(
            InMemoryPairedNodeStore(listOf(ROOTFORGE_PAIRED)),
            FakeExternalNodeTransport { _, _, _ -> RootForgeResult.Failure(FailureKind.REMOTE_ERROR, "node refused it", remoteCategory = "external_control_disabled") },
        )
        val result = MessengerFleetTransport(gateway) { true }.sendCommand(rootforge, "rootforge.adb.shell", "{}")
        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("external_control_disabled"))
    }
}
