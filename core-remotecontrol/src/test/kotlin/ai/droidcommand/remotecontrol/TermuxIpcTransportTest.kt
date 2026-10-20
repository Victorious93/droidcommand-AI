package ai.droidcommand.remotecontrol

import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MAJOR
import ai.droidcommand.rootforge.BRIDGE_PROTOCOL_MINOR
import ai.droidcommand.security.FleetHonestyStatus
import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.security.KnownFleet
import ai.droidcommand.security.KnownNodeSigningCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TermuxIpcTransportTest {
    private val now = 10_000_000L

    @Test
    fun `builds RUN_COMMAND extras with the frozen camelCase envelope`() {
        val envelope = envelope(
            requestId = "req-termux-1",
            targetNodeRef = "com.termux",
            actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
            timestampMs = now,
        )
        val request = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
            .buildRequest(envelope)
        assertEquals(FleetRemoteEnvelope.TERMUX_RUN_COMMAND_ACTION, request.action)
        assertEquals("com.termux.RUN_COMMAND", request.action)
        assertEquals("com.termux", request.packageName)
        assertEquals(TermuxIpcRemoteBinding.EXTRA, FleetRemoteEnvelope.TERMUX_JSON_EXTRA)
        assertEquals("ai.droidcommand.REMOTE_ENVELOPE_JSON", request.extras.keys.single())
        val json = request.envelopeJson
        assertTrue(json.contains("\"requestId\":\"req-termux-1\""), json)
        assertTrue(json.contains("\"targetNodeRef\":\"com.termux\""), json)
        assertTrue(json.contains("\"actionRef\":\"capabilities.get\""), json)
        assertTrue(json.contains("\"nonce\""), json)
        assertTrue(json.contains("\"timestampMs\":$now"), json)
        assertTrue(json.contains("\"ttlMs\""), json)
        assertTrue(json.contains("\"destructive\""), json)
        assertFalse(json.contains("\"auth\""), json)
        assertFalse(json.contains("\"issuedAt\""), json)
        val decoded = TermuxIpcRemoteBinding.decodeBlob(json)
        assertEquals(envelope.requestId, decoded.requestId)
        assertEquals(envelope.actionRef, decoded.actionRef)
        assertEquals(envelope.timestampMs, decoded.timestampMs)
    }

    @Test
    fun `rejects expired envelopes when now is past timestampMs plus ttl`() {
        val transport = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
        val rejected = assertIs<TermuxIpcResult.Rejected>(
            transport.submit(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET,
                    timestampMs = now - 120_001,
                    ttlMs = 120_000,
                ),
            ),
        )
        assertEquals(RemoteErrorCategory.EXPIRED, rejected.category)
        assertEquals("expired", rejected.response?.error?.category)
        assertFalse(rejected.response!!.ok)
    }

    @Test
    fun `rejects a timestamp more than 120s in the future as expired`() {
        val transport = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
        val rejected = assertIs<TermuxIpcResult.Rejected>(
            transport.submit(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
                    timestampMs = now + 120_001,
                ),
            ),
        )
        assertEquals(RemoteErrorCategory.EXPIRED, rejected.category)
    }

    @Test
    fun `past validity is TTL-only so a 200s-old envelope with 300s ttl is accepted`() {
        val transport = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
        val result = transport.prepare(
            envelope(
                actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
                timestampMs = now - 200_000,
                ttlMs = 300_000,
            ),
        )
        assertIs<TermuxIpcResult.Ready>(result)
    }

    @Test
    fun `destructive remote command is confirmation_required and is not sent`() {
        var sent = 0
        val transport = TermuxIpcTransport(
            nowMs = { now },
            externalControlEnabled = true,
            sender = TermuxIpcSender { sent += 1; TermuxIpcResult.Ready(it) },
        )
        val rejected = assertIs<TermuxIpcResult.Rejected>(
            transport.submit(
                envelope(
                    actionRef = "shell.exec",
                    timestampMs = now,
                    destructive = false,
                ),
                FleetRemoteEnvelope.OP_REMOTE_COMMAND,
            ),
        )
        assertEquals(RemoteErrorCategory.CONFIRMATION_REQUIRED, rejected.category)
        assertEquals(0, sent)
        assertEquals("confirmation_required", rejected.response?.error?.category)
    }

    @Test
    fun `advisory destructive still cannot lower a classified wipe`() {
        val transport = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
        val rejected = assertIs<TermuxIpcResult.Rejected>(
            transport.submit(
                envelope(actionRef = "wipe", timestampMs = now, destructive = false),
                FleetRemoteEnvelope.OP_REMOTE_COMMAND,
            ),
        )
        assertEquals(RemoteErrorCategory.CONFIRMATION_REQUIRED, rejected.category)
    }

    @Test
    fun `config import apply is confirmation_required and is not sent`() {
        var sent = 0
        val transport = TermuxIpcTransport(
            nowMs = { now },
            externalControlEnabled = true,
            sender = TermuxIpcSender { sent += 1; TermuxIpcResult.Ready(it) },
        )
        val rejected = assertIs<TermuxIpcResult.Rejected>(
            transport.submit(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_CONFIG_IMPORT,
                    timestampMs = now,
                    dryRun = false,
                    args = mapOf("bundle" to "{}"),
                ),
            ),
        )
        assertEquals(RemoteErrorCategory.CONFIRMATION_REQUIRED, rejected.category)
        assertEquals(0, sent)
    }

    @Test
    fun `config import dry-run is not confirmation-gated`() {
        val transport = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
        val result = transport.prepare(
            envelope(
                actionRef = FleetRemoteEnvelope.OP_CONFIG_IMPORT,
                timestampMs = now,
                dryRun = true,
                args = mapOf("bundle" to "{}", "dryRun" to "true"),
            ),
        )
        assertIs<TermuxIpcResult.Ready>(result)
    }

    @Test
    fun `node status get is never confirmation-gated even when advisory destructive is true`() {
        val transport = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
        val result = transport.prepare(
            envelope(
                actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET,
                timestampMs = now,
                destructive = true,
            ),
        )
        assertIs<TermuxIpcResult.Ready>(result)
        assertFalse(
            TermuxIpcTransport.isDestructive(
                envelope(actionRef = FleetRemoteEnvelope.OP_NODE_STATUS_GET, destructive = true),
                FleetRemoteEnvelope.OP_NODE_STATUS_GET,
            ),
        )
    }

    @Test
    fun `external control stays off by default`() {
        val transport = TermuxIpcTransport(nowMs = { now })
        val rejected = assertIs<TermuxIpcResult.Rejected>(
            transport.submit(
                envelope(actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET, timestampMs = now),
            ),
        )
        assertEquals(RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED, rejected.category)
    }

    @Test
    fun `parses the frozen BridgeResponse result extra`() {
        val ok = """{"protocol_major":1,"request_id":"r-1","node_id":"com.termux","ok":true,"result":{"pong":true}}"""
        val parsed = TermuxIpcTransport(nowMs = { now }).parseResponse(ok)
        assertEquals(BRIDGE_PROTOCOL_MAJOR, parsed.protocolMajor)
        assertEquals("r-1", parsed.requestId)
        assertEquals("com.termux", parsed.nodeId)
        assertTrue(parsed.ok)
        assertEquals("true", parsed.result?.get("pong").toString())
        assertNull(parsed.error)

        val err = """{"protocol_major":1,"request_id":"r-1","node_id":"com.termux","ok":false,""" +
            """"error":{"category":"confirmation_required","message":"needs confirm"}}"""
        val failed = TermuxIpcRemoteBinding.parseResponse(err)
        assertFalse(failed.ok)
        assertEquals("confirmation_required", failed.error?.category)
        assertEquals(TermuxIpcRemoteBinding.RESPONSE_EXTRA, FleetRemoteEnvelope.TERMUX_RESPONSE_EXTRA)
        assertEquals("ai.droidcommand.REMOTE_RESPONSE_JSON", TermuxIpcRemoteBinding.RESPONSE_EXTRA)
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MAJOR)
        assertEquals(1, FleetRemoteEnvelope.PROTOCOL_MINOR)
        assertEquals(1, BRIDGE_PROTOCOL_MINOR)
    }

    @Test
    fun `unwired sender never broadcasts`() {
        val transport = TermuxIpcTransport(nowMs = { now }, externalControlEnabled = true)
        val rejected = assertIs<TermuxIpcResult.Rejected>(
            transport.submit(
                envelope(actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET, timestampMs = now),
            ),
        )
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, rejected.category)
        assertTrue(rejected.reason.contains("not wired"))
    }

    @Test
    fun `catalogue is TERMUX_IPC com termux PENDING with the supported ops`() {
        val suite = KnownFleet.VICTORSUITE
        assertEquals(FleetIntegrationClass.TERMUX_IPC, suite.integrationClass)
        assertEquals("com.termux", suite.packageOrBinary)
        assertEquals(listOf("com.termux"), suite.androidPackages)
        assertEquals(FleetHonestyStatus.PARTIAL, suite.status)
        assertNotEqualsLive(suite.status)
        assertTrue(suite.transports.any { it.contains("PENDING") })
        assertTrue(suite.transports.any { it.contains("capabilities.get") })
        assertTrue(suite.transports.any { it.contains("remote.command") })
        assertTrue(suite.transports.any { it.contains("macro.step") })
        assertTrue(suite.transports.any { it.contains("macro.run") })
        assertTrue(suite.transports.any { it.contains("node.status.get") })
        assertTrue(suite.transports.any { it.contains("config.export") })
        assertTrue(suite.transports.any { it.contains("config.import") })
        assertTrue(suite.notes.contains("PENDING"))
        assertTrue(suite.notes.contains("PR #48"))
        assertTrue(suite.notes.contains("a6f4e2a"))
        assertTrue(suite.refusalMessage.contains("PENDING"))
        assertEquals(VictorSuiteRegistry.TARGET, RemoteCommandTargetRegistry.byId("victorsuite"))
        assertEquals(VictorSuiteRegistry.TARGET, RemoteCommandTargetRegistry.byPackage("com.termux"))
        assertEquals(FleetIntegrationClass.TERMUX_IPC, VictorSuiteRegistry.TARGET.integrationClass)
        assertEquals("PENDING", VictorSuiteRegistry.TARGET.status)
        assertEquals("com.termux.RUN_COMMAND", VictorSuiteRegistry.TARGET.bridgeAction)
        assertEquals(VictorSuiteRegistry.OPS, VictorSuiteRegistry.TARGET.cataloguedOps)
        assertTrue(VictorSuiteRegistry.TARGET.remoteCommandDefaultOff)
        assertFalse(VictorSuiteRegistry.TARGET.enforcePin)
        assertNull(VictorSuiteRegistry.TARGET.documentedDebugCertSha256)
        assertEquals(
            KnownFleet.VICTORSUITE.expectedSigningCertSha256,
            listOf(
                KnownNodeSigningCatalog.VICTORSUITE_DEBUG_SHA256,
                KnownNodeSigningCatalog.VICTORSUITE_RELEASE_SHA256,
            ),
        )
        val row = FleetDashboardCatalog.fromKnown(suite)
        assertTrue(row.pendingDeviceTest)
        val dashboard = FleetDashboardRows.build(
            members = listOf(row),
            pairings = emptyList(),
            snapshots = emptyList(),
            nowMs = now,
        ).single()
        assertEquals(FleetDashboardRows.STATUS_PENDING, dashboard.statusLabel)
        assertEquals("PENDING", dashboard.statusLabel)
    }

    private fun assertNotEqualsLive(status: FleetHonestyStatus) {
        assertTrue(status != FleetHonestyStatus.LIVE)
    }
}
