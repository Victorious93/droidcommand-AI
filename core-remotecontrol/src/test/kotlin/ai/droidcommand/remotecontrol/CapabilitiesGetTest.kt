package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilitiesGetTest {
    @Test
    fun `capabilities get advertises protocol minor 1 and op descriptors`() {
        val dispatcher = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(inboundConfig()),
            ReplayGuard(nowMs = { 8_000L }),
        )
        val result = assertIs<RemoteDispatchResult.Capabilities>(
            dispatcher.dispatch(
                envelope(actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET, timestampMs = 8_000L),
                source(),
                FleetRemoteEnvelope.OP_CAPABILITIES_GET,
            ),
        )
        assertEquals(1, result.protocolMajor)
        assertEquals(1, result.protocolMinor)
        assertEquals(
            listOf(
                "capabilities.get",
                "remote.command",
                "macro.step",
                "macro.run",
                "node.status.get",
                "config.export",
                "config.import",
            ),
            result.opNames,
        )
        val descriptors = result.ops
        assertEquals(OpKind.READ, descriptors.single { it.name == "capabilities.get" }.kind)
        assertEquals("read", descriptors.single { it.name == "capabilities.get" }.kind.wire)
        assertTrue(descriptors.single { it.name == "capabilities.get" }.remoteAllowed)
        assertFalse(descriptors.single { it.name == "capabilities.get" }.requiresConfirm)
        assertTrue(descriptors.single { it.name == "remote.command" }.requiresConfirm)
        assertEquals(OpKind.ACTION, descriptors.single { it.name == "macro.run" }.kind)
        val status = descriptors.single { it.name == "node.status.get" }
        assertEquals(OpKind.READ, status.kind)
        assertTrue(status.remoteAllowed)
        assertFalse(status.requiresConfirm)
        val export = descriptors.single { it.name == "config.export" }
        val importOp = descriptors.single { it.name == "config.import" }
        assertEquals(OpKind.ACTION, export.kind)
        assertFalse(export.remoteAllowed)
        assertFalse(export.requiresConfirm)
        assertEquals(OpKind.ACTION, importOp.kind)
        assertFalse(importOp.remoteAllowed)
        assertTrue(importOp.requiresConfirm)
    }
}
