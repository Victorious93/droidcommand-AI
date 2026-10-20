package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NodeStatusSnapshotCodecTest {
    private val mesh = NodeStatusSnapshot(
        nodeId = "mesh-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        packageName = "com.victorious.mesh",
        connectionClass = FleetIntegrationClass.EXTERNAL_NODE,
        updatedAtMs = 1_760_920_000_000L,
        domains = listOf(
            StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.OK, "headscale up"),
            StatusTrayDomainStatus(StatusTrayDomain.VPN, StatusTrayState.DEGRADED, "exit node lag"),
            StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, StatusTrayState.OK),
        ),
        capability = "node.status.get",
        actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
    )

    @Test
    fun `encode then decode round-trips frozen snapshot fields`() {
        val decoded = NodeStatusSnapshotCodec.decode(NodeStatusSnapshotCodec.encode(mesh))
        assertEquals(mesh.nodeId, decoded.nodeId)
        assertEquals(mesh.packageName, decoded.packageName)
        assertEquals(mesh.connectionClass, decoded.connectionClass)
        assertEquals(mesh.updatedAtMs, decoded.updatedAtMs)
        assertEquals(mesh.domains.map { it.name to it.state to it.detail }, decoded.domains.map { it.name to it.state to it.detail })
        assertEquals("mesh-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", decoded.sourceNodeRef)
        assertEquals(mesh.updatedAtMs, decoded.receivedAtMs)
        assertEquals(true, decoded.healthy)
        assertTrue(NodeStatusSnapshotCodec.encode(mesh).contains("\"nodeId\""))
        assertTrue(NodeStatusSnapshotCodec.encode(mesh).contains("\"connectionClass\""))
        assertTrue(NodeStatusSnapshotCodec.encode(mesh).contains("\"updatedAtMs\""))
        assertTrue(NodeStatusSnapshotCodec.encode(mesh).contains("\"domains\""))
        assertTrue(NodeStatusSnapshotCodec.encode(mesh).contains("\"mesh\""))
        assertTrue(NodeStatusSnapshotCodec.encode(mesh).contains("\"node_health\""))
    }

    @Test
    fun `unknown domain name decodes as other and round-trips the original name`() {
        val raw = """
            {"nodeId":"n1","connectionClass":"TERMUX_IPC","updatedAtMs":9,
             "domains":[{"name":"battery","state":"ok","detail":"82%"}]}
        """.trimIndent()
        val decoded = NodeStatusSnapshotCodec.decode(raw)
        assertEquals(StatusTrayDomain.OTHER, decoded.domains.single().name)
        assertEquals("battery", decoded.domains.single().originalName)
        assertEquals("battery", decoded.domains.single().wireName())
        assertEquals(FleetIntegrationClass.TERMUX_IPC, decoded.connectionClass)
        val again = NodeStatusSnapshotCodec.decode(NodeStatusSnapshotCodec.encode(decoded))
        assertEquals("battery", again.domains.single().wireName())
    }

    @Test
    fun `legacy single-domain JSON still decodes via aliases`() {
        val raw = """
            {"sourceNodeRef":"node-1","domain":"BROWSER","healthy":true,
             "actionRef":"status","receivedAtMs":11}
        """.trimIndent()
        val decoded = NodeStatusSnapshotCodec.decode(raw)
        assertEquals("node-1", decoded.nodeId)
        assertEquals(StatusTrayDomain.BROWSER, decoded.domain)
        assertEquals(true, decoded.healthy)
        assertEquals(11L, decoded.updatedAtMs)
    }

    @Test
    fun `list encode-decode and BridgeResponse round-trip`() {
        val sms = NodeStatusSnapshot.of(
            sourceNodeRef = "sms-1",
            domain = StatusTrayDomain.SMS,
            healthy = true,
            receivedAtMs = 4,
            packageName = FleetRemoteEnvelope.VICTOR_SMS_PACKAGE,
        )
        val encoded = NodeStatusSnapshotCodec.encodeList(listOf(mesh, sms))
        val decoded = NodeStatusSnapshotCodec.decodeList(encoded)
        assertEquals(listOf(mesh.nodeId, "sms-1"), decoded.map { it.nodeId })
        val response = NodeStatusSnapshotCodec.encodeBridgeResponse(
            snapshots = decoded,
            requestId = "req-status",
            nodeId = FleetRemoteEnvelope.TARGET_CONTROLLER,
            aggregated = true,
        )
        assertTrue(response.contains("\"snapshots\""))
        assertEquals(listOf(mesh.nodeId, "sms-1"), NodeStatusSnapshotCodec.decodeBridgeResponse(response).map { it.nodeId })
        val single = NodeStatusSnapshotCodec.encodeBridgeResponse(
            snapshots = listOf(mesh),
            requestId = "req-one",
            nodeId = mesh.nodeId,
            aggregated = false,
        )
        assertEquals(mesh.nodeId, NodeStatusSnapshotCodec.decodeBridgeResponse(single).single().nodeId)
    }

    @Test
    fun `BridgeResponse errors map onto RemoteErrorCategory`() {
        val raw = NodeStatusSnapshotCodec.encodeBridgeError(
            RemoteErrorCategory.NOT_PAIRED,
            requestId = "r1",
            message = "not paired",
        )
        val error = assertFailsWith<StatusTrayError> {
            NodeStatusSnapshotCodec.decodeBridgeResponse(raw)
        }
        assertEquals(RemoteErrorCategory.NOT_PAIRED, error.category)
        assertEquals("not paired", error.message)
    }

    @Test
    fun `privacy guard refuses secrets in detail or payload`() {
        assertFailsWith<IllegalArgumentException> {
            NodeStatusSnapshotCodec.encode(
                mesh.copy(domains = listOf(StatusTrayDomainStatus(StatusTrayDomain.SMS, StatusTrayState.OK, "codeWord=abc"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NodeStatusSnapshotCodec.encode(
                mesh.copy(payload = mapOf("signingKey" to "nope")),
            )
        }
    }

    @Test
    fun `dispatcher node status get returns aggregated tray`() {
        val tray = InMemoryRemoteStatusTray()
        tray.consume(mesh)
        val dispatcher = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(inboundConfig()),
            ReplayGuard(nowMs = { 1_760_920_000_000L }),
            tray,
        )
        val all = assertIs<RemoteDispatchResult.StatusTray>(
            dispatcher.dispatch(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
                    timestampMs = 1_760_920_000_000L,
                    args = emptyMap(),
                ),
                source(),
                FleetRemoteEnvelope.OP_NODE_STATUS_GET,
            ),
        )
        assertEquals(listOf(mesh.nodeId), all.snapshots.map { it.nodeId })
        val missing = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
                    timestampMs = 1_760_920_000_000L,
                    args = mapOf("nodeId" to "nope"),
                    nonce = TEST_NONCE_B,
                ),
                source(),
                FleetRemoteEnvelope.OP_NODE_STATUS_GET,
            ),
        )
        assertEquals(RemoteErrorCategory.NOT_FOUND, missing.category)
    }

    @Test
    fun `capability off when node status get is not advertised`() {
        val dispatcher = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(inboundConfig()),
            ReplayGuard(nowMs = { 8_000L }),
            InMemoryRemoteStatusTray(),
            advertisedOps = listOf(
                OpDescriptor(FleetRemoteEnvelope.OP_CAPABILITIES_GET, OpKind.READ, remoteAllowed = true, requiresConfirm = false),
            ),
        )
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET, timestampMs = 8_000L),
                source(),
                FleetRemoteEnvelope.OP_NODE_STATUS_GET,
            ),
        )
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, rejected.category)
    }
}
